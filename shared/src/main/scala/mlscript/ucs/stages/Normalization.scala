
package mlscript.ucs.stages

import mlscript.ucs.{Lines, LinesOps, VariableGenerator}
import mlscript.ucs.context.{Context, ScrutineeData}
import mlscript.ucs.core._
import mlscript.ucs.display.{showNormalizedTerm, showSplit}
import mlscript.ucs.helpers._
import mlscript.pretyper.Scope
import mlscript.pretyper.symbol._
import mlscript.{App, CaseOf, Fld, FldFlags, Let, Loc, Sel, Term, Tup, Var, StrLit}
import mlscript.{CaseBranches, Case, Wildcard, NoCases}
import mlscript.Message, Message.MessageContext
import mlscript.utils._, shorthands._
import mlscript.pretyper.{Traceable, Diagnosable}

trait Normalization { self: Traceable with Diagnosable =>
  import Normalization._

  private def concatImpl(these: Split, those: Split)(implicit context: Context, generatedVars: Set[Var]): Split =
    if (these.hasElse) these else (these match {
      case these @ Split.Cons(head, tail) =>
        println(s"found a cons: $head")
        if (head.continuation.hasElse) {
          these.copy(tail = concatImpl(tail, those))
        } else {
          println("found a branch without default, duplicating...")
          val newHead = head.copy(continuation = concat(head.continuation, those))
          these.copy(head = newHead, tail = concatImpl(tail, those))
        }
      case these @ Split.Let(_, nme, _, tail) =>
        if (those.freeVars contains nme) {
          val fresh = context.freshShadowed()
          val thoseWithShadowed = Split.Let(false, nme, fresh, those)
          val concatenated = these.copy(tail = concatImpl(tail, thoseWithShadowed))
          Split.Let(false, fresh, nme, concatenated)
        } else {
          these.copy(tail = concatImpl(tail, those)(context, generatedVars + nme))
        }
      case _: Split.Else => these
      case Split.Nil => those.withoutBindings(generatedVars)
    })
  
  private def concat(these: Split, those: Split)(implicit context: Context, generatedVars: Set[Var]): Split =
    trace(s"concat <== ${generatedVars.mkString("{", ", ", "}")}") {
      println(s"these: ${showSplit(these)}")
      println(s"those: ${showSplit(those)}")
      concatImpl(these, those)
    }(sp => s"concat => ${showSplit(sp)}")

  /**
    * Normalize core abstract syntax to MLscript syntax.
    *
    * @param split the split to normalize
    * @return the normalized term
    */ 
  @inline protected def normalize(split: Split)(implicit context: Context): Term = normalizeToTerm(split)(context, Set.empty)

  private def normalizeToTerm(split: Split)(implicit context: Context, generatedVars: Set[Var]): Term = trace("normalizeToTerm <==") {
    split match {
      case Split.Cons(Branch(scrutinee, Pattern.Name(nme), continuation), tail) =>
        println(s"normalizing name pattern ${scrutinee.name} is ${nme.name}")
        Let(false, nme, scrutinee, normalizeToTerm(concat(continuation, tail)))
      // Skip Boolean conditions as scrutinees, because they only appear once.
      case Split.Cons(Branch(test, pattern @ Pattern.Class(nme @ Var("true"), _), continuation), tail) =>
        println(s"normalizing true pattern: ${test.name} is true")
        val trueBranch = normalizeToTerm(concat(continuation, tail))
        val falseBranch = normalizeToCaseBranches(tail)
        CaseOf(test, Case(nme, trueBranch, falseBranch)(refined = false))
      case Split.Cons(Branch(ScrutineeData.WithVar(scrutinee, scrutineeVar), pattern @ Pattern.Literal(literal), continuation), tail) =>
        println(s"normalizing literal pattern: ${scrutineeVar.name} is $literal")
        val concatenatedTrueBranch = concat(continuation, tail)
        println(s"true branch: ${showSplit(concatenatedTrueBranch)}")
        val trueBranch = normalizeToTerm(specialize(concatenatedTrueBranch, true)(scrutineeVar, scrutinee, pattern, context))
        println(s"false branch: ${showSplit(tail)}")
        val falseBranch = normalizeToCaseBranches(specialize(tail, false)(scrutineeVar, scrutinee, pattern, context))
        CaseOf(scrutineeVar, Case(literal, trueBranch, falseBranch)(refined = false))
      case Split.Cons(Branch(ScrutineeData.WithVar(scrutinee, scrutineeVar), pattern @ Pattern.Class(nme, rfd), continuation), tail) =>
        println(s"normalizing class pattern: ${scrutineeVar.name} is ${nme.name}")
        // println(s"match ${scrutineeVar.name} with $nme (has location: ${nme.toLoc.isDefined})")
        val trueBranch = normalizeToTerm(specialize(concat(continuation, tail), true)(scrutineeVar, scrutinee, pattern, context))
        val falseBranch = normalizeToCaseBranches(specialize(tail, false)(scrutineeVar, scrutinee, pattern, context))
        CaseOf(scrutineeVar, Case(nme, trueBranch, falseBranch)(refined = rfd))
      case Split.Cons(Branch(scrutinee, pattern, continuation), tail) =>
        println(s"unknown pattern $pattern")
        throw new NormalizationException((msg"Unsupported pattern: ${pattern.toString}" -> pattern.toLoc) :: Nil)
      case Split.Let(rec, Var("_"), rhs, tail) => normalizeToTerm(tail)
      case Split.Let(_, nme, _, tail) if context.isScrutineeVar(nme) && generatedVars.contains(nme) =>
        println(s"normalizing let binding of generated variable: ${nme.name}")
        normalizeToTerm(tail)
      case Split.Let(rec, nme, rhs, tail) =>
        println(s"normalizing let binding ${nme.name}")
        val newDeclaredBindings = if (context.isGeneratedVar(nme)) generatedVars + nme else generatedVars
        Let(rec, nme, rhs, normalizeToTerm(tail)(context, newDeclaredBindings))
      case Split.Else(default) =>
        println(s"normalizing default: $default")
        default
      case Split.Nil =>
        println(s"normalizing nil")
        ???
    }
  }(split => "normalizeToTerm ==> " + showNormalizedTerm(split))

  private def normalizeToCaseBranches(split: Split)(implicit context: Context, generatedVars: Set[Var]): CaseBranches =
    trace(s"normalizeToCaseBranches <== $split") {
      split match {
        // case Split.Cons(head, Split.Nil) => Case(head.pattern, normalizeToTerm(head.continuation), NoCases)
        case other: Split.Cons => Wildcard(normalizeToTerm(other))
        case Split.Let(rec, Var("_"), rhs, tail) => normalizeToCaseBranches(tail)
        case Split.Let(_, nme, _, tail) if context.isScrutineeVar(nme) && generatedVars.contains(nme) =>
          normalizeToCaseBranches(tail)
        case Split.Let(rec, nme, rhs, tail) =>
          val newDeclaredBindings = if (context.isGeneratedVar(nme)) generatedVars + nme else generatedVars
          normalizeToCaseBranches(tail)(context, newDeclaredBindings) match {
            case NoCases => Wildcard(rhs)
            case Wildcard(term) => Wildcard(Let(rec, nme, rhs, term))
            case _: Case => die
          }
        case Split.Else(default) => Wildcard(default)
        case Split.Nil => NoCases
      }
    }(r => "normalizeToCaseBranches ==> ")

  // Specialize `split` with the assumption that `scrutinee` matches `pattern`.
  private def specialize
      (split: Split, matchOrNot: Bool)
      (implicit scrutineeVar: Var, scrutinee: ScrutineeData, pattern: Pattern, context: Context): Split =
  trace[Split](s"S${if (matchOrNot) "+" else "-"} <== ${scrutineeVar.name} is ${pattern}") {
    (matchOrNot, split) match {
      // Name patterns are translated to let bindings.
      case (_, Split.Cons(Branch(otherScrutineeVar, Pattern.Name(alias), continuation), tail)) =>
        Split.Let(false, alias, otherScrutineeVar, specialize(continuation, matchOrNot))
      case (_, split @ Split.Cons(head @ Branch(test, Pattern.Class(Var("true"), _), continuation), tail)) if context.isTestVar(test) =>
        println(s"found a Boolean test: $test is true")
        val trueBranch = specialize(continuation, matchOrNot)
        val falseBranch = specialize(tail, matchOrNot)
        split.copy(head = head.copy(continuation = trueBranch), tail = falseBranch)
      // Class pattern. Positive.
      case (true, split @ Split.Cons(head @ Branch(ScrutineeData.WithVar(otherScrutinee, otherScrutineeVar), Pattern.Class(otherClassName, rfd), continuation), tail)) =>
        val otherClassSymbol = getClassLikeSymbol(otherClassName)
        lazy val specializedTail = {
          println(s"specialized next")
          specialize(tail, true)
        }
        if (scrutinee === otherScrutinee) {
          println(s"scrutinee: ${scrutineeVar.name} === ${otherScrutineeVar.name}")
          pattern match {
            case Pattern.Class(className, rfd2) =>
              assert(rfd === rfd2) // TODO: raise warning instead of crash
              val classSymbol = getClassLikeSymbol(className)
              if (classSymbol === otherClassSymbol) {
                println(s"Case 1: class name: $className === $otherClassName")
                val specialized = specialize(continuation, true)
                if (specialized.hasElse) {
                  println("tail is discarded")
                  specialized.withDiagnostic(
                    msg"Discarded split because of else branch" -> None // TODO: Synthesize locations
                  )
                } else {
                  specialized ++ specialize(tail, true)
                }
              } else if (otherClassSymbol.baseTypes contains classSymbol) {
                println(s"Case 2: $otherClassName <: $className")
                split
              } else {
                println(s"Case 3: $className and $otherClassName are unrelated")
                specializedTail
              }
            case _ => throw new NormalizationException((msg"Incompatible: ${pattern.toString}" -> pattern.toLoc) :: Nil)
          }
        } else {
          println(s"scrutinee: ${scrutineeVar.name} =/= ${otherScrutineeVar.name}")
          split.copy(
            head = head.copy(continuation = specialize(continuation, true)),
            tail = specializedTail
          )
        }
      // Class pattern. Negative
      case (false, split @ Split.Cons(head @ Branch(ScrutineeData.WithVar(otherScrutinee, otherScrutineeVar), Pattern.Class(otherClassName, rfd), continuation), tail)) =>
        val otherClassSymbol = getClassLikeSymbol(otherClassName)
        if (scrutinee === otherScrutinee) {
          println(s"scrutinee: ${scrutineeVar.name} === ${otherScrutineeVar.name}")
          pattern match {
            case Pattern.Class(className, rfd2) =>
              assert(rfd === rfd2) // TODO: raise warning instead of crash
              val classSymbol = getClassLikeSymbol(className)
              if (className === otherClassName) {
                println(s"Case 1: class name: $otherClassName === $className")
                specialize(tail, false)
              } else if (otherClassSymbol.baseTypes contains classSymbol) {
                println(s"Case 2: class name: $otherClassName <: $className")
                Split.Nil
              } else {
                println(s"Case 3: class name: $otherClassName and $className are unrelated")
                split.copy(tail = specialize(tail, false))
              }
            case _ => throw new NormalizationException((msg"Incompatible: ${pattern.toString}" -> pattern.toLoc) :: Nil)
          }
        } else {
          println(s"scrutinee: ${scrutineeVar.name} =/= ${otherScrutineeVar.name}")
          split.copy(
            head = head.copy(continuation = specialize(continuation, matchOrNot)),
            tail = specialize(tail, matchOrNot)
          )
        }
      // Literal pattern. Positive.
      case (true, split @ Split.Cons(head @ Branch(ScrutineeData.WithVar(otherScrutinee, otherScrutineeVar), Pattern.Literal(otherLiteral), continuation), tail)) =>
        if (scrutinee === otherScrutinee) {
          println(s"scrutinee: ${scrutineeVar.name} is ${otherScrutineeVar.name}")
          pattern match {
            case Pattern.Literal(literal) if literal === otherLiteral =>
              val specialized = specialize(continuation, true)
              if (specialized.hasElse) {
                println("tail is discarded")
                specialized.withDiagnostic(
                  msg"Discarded split because of else branch" -> None // TODO: Synthesize locations
                )
              } else {
                specialized ++ specialize(tail, true)
              }
            case _ => specialize(tail, true)
          }
        } else {
          println(s"scrutinee: ${scrutineeVar.name} is NOT ${otherScrutineeVar.name}")
          split.copy(
            head = head.copy(continuation = specialize(continuation, true)),
            tail = specialize(tail, true)
          )
        }
      // Literal pattern. Negative.
      case (false, split @ Split.Cons(head @ Branch(ScrutineeData.WithVar(otherScrutinee, otherScrutineeVar), Pattern.Literal(otherLiteral), continuation), tail)) =>
        if (scrutinee === otherScrutinee) {
          println(s"scrutinee: ${scrutineeVar.name} is ${otherScrutineeVar.name}")
          pattern match {
            case Pattern.Literal(literal) if literal === otherLiteral =>
              specialize(tail, false)
            case _ =>
              // No need to check `continuation` because literals don't overlap.
              split.copy(tail = specialize(tail, false))
          }
        } else {
          println(s"scrutinee: ${scrutineeVar.name} is NOT ${otherScrutineeVar.name}")
          split.copy(
            head = head.copy(continuation = specialize(continuation, false)),
            tail = specialize(tail, false)
          )
        }
      // Other patterns. Not implemented.
      case (_, Split.Cons(Branch(otherScrutineeVar, pattern, continuation), tail)) =>
        println(s"unsupported pattern: $pattern")
        throw new NormalizationException((msg"Unsupported pattern: ${pattern.toString}" -> pattern.toLoc) :: Nil)
      case (_, let @ Split.Let(_, nme, _, tail)) =>
        println(s"let binding $nme, go next")
        let.copy(tail = specialize(tail, matchOrNot))
      // Ending cases remain the same.
      case (_, end @ (Split.Else(_) | Split.Nil)) => println("the end"); end
    }
  }(showSplit(s"S${if (matchOrNot) "+" else "-"} ==>", _))
}

object Normalization {
  private def getClassLikeSymbol(className: Var): TypeSymbol =
    className.symbolOption.flatMap(_.typeSymbolOption) match {
      case N => throw new NormalizationException(msg"class name is not resolved" -> className.toLoc :: Nil)
      case S(typeSymbol) => typeSymbol
    }

  class NormalizationException(val messages: Ls[Message -> Opt[Loc]]) extends Throwable {
    def this(message: Message, location: Opt[Loc]) = this(message -> location :: Nil)
  }
}