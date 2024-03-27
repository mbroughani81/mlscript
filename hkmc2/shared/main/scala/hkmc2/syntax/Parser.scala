package hkmc2
package syntax

import sourcecode.{Name, Line}
import mlscript.utils.*, shorthands.*
import hkmc2.Message._
import BracketKind._

import Parser.*


abstract class Parser(
  origin: Origin,
  tokens: Ls[TokLoc],
  raiseFun: Diagnostic => Unit,
  val dbg: Bool,
  // fallbackLoc: Opt[Loc], description: Str = "input",
):
  outer =>
  
  protected def doPrintDbg(msg: => Str): Unit
  protected def printDbg(msg: => Any): Unit =
    doPrintDbg("│ " * this.indent + msg)
  
  protected var indent = 0
  private var _cur: Ls[TokLoc] = tokens
  
  final def rec(tokens: Ls[Stroken -> Loc], fallbackLoc: Opt[Loc], description: Str): Parser =
    new Parser(origin, tokens, raiseFun, dbg
        // , fallbackLoc, description
    ) {
      def doPrintDbg(msg: => Str): Unit = outer.printDbg("> " + msg)
    }
  
  def resetCur(newCur: Ls[TokLoc]): Unit =
    _cur = newCur
    // _modifiersCache = ModifierSet.empty
  
  private lazy val lastLoc =
    tokens.lastOption.map(_._2.right)//.orElse(fallbackLoc)
  
  private def summarizeCur =
    Lexer.printTokens(_cur.take(5)) + (if _cur.sizeIs > 5 then "..." else "")
  
  private def cur(implicit l: Line, n: Name) =
    if dbg then printDbg(s"? ${n.value}\t\tinspects ${summarizeCur}    [at l.${l.value}]")
    while !_cur.isEmpty && (_cur.head._1 match {
      case COMMENT(_) => true
      case _ => false
    }) do consume
    _cur
  
  final def consume(implicit l: Line, n: Name): Unit =
    if dbg then printDbg(s"! ${n.value}\t\tconsumes ${Lexer.printTokens(_cur.take(1))}    [at l.${l.value}]")
    resetCur(_cur.tailOption.getOrElse(Nil)) // FIXME throw error if empty?
  
  private def yeetSpaces: Ls[TokLoc] =
    cur.dropWhile(tkloc =>
      (tkloc._1 === SPACE
      || tkloc._1.isInstanceOf[COMMENT] // TODO properly retrieve and store all comments in AST?
      ) && { consume; true })
  
  // final def raise(mkDiag: => Diagnostic)(implicit fe: FoundErr = false): Unit =
  //   if (!foundErr) raiseFun(mkDiag)
  final def raise(mkDiag: => Diagnostic): Unit =
    raiseFun(mkDiag)
  
  private def errExpr =
    Tree.Empty // TODO FIXME produce error term instead
  
  final def err(msgs: Ls[Message -> Opt[Loc]]): Unit =
    raise(ErrorReport(msgs, newDefs = true, source = Diagnostic.Source.Parsing))
  
  final def parseAll[R](parser: => R): R =
    val res = parser
    cur match
      case c @ (tk, tkl) :: _ =>
        val (relevantToken, rl) = c.dropWhile(_._1 === SPACE).headOption.getOrElse(tk, tkl)
        err(msg"Expected end of input; found ${relevantToken.describe} instead" -> S(rl) :: Nil)
      case Nil => ()
    res
  
  final def concludeWith[R](f: this.type => R): R = {
    val res = f(this)
    cur.dropWhile(tk => (tk._1 === SPACE || tk._1 === NEWLINE) && { consume; true }) match {
      case c @ (tk, tkl) :: _ =>
        val (relevantToken, rl) = c.dropWhile(_._1 === SPACE).headOption.getOrElse(tk, tkl)
        err(msg"Unexpected ${relevantToken.describe} here" -> S(rl) :: Nil)
      case Nil => ()
    }
    printDbg(s"Concluded with $res")
    res
  }
  
  def block: Ls[Tree] = blockOf(ParseRule.prefixRules)
  
  def blockOf(rule: ParseRule[Tree]): Ls[Tree] = cur match
    case Nil => Nil
    case (NEWLINE, _) :: _ => consume; blockOf(rule)
    case (SPACE, _) :: _ => consume; blockOf(rule)
    case (id: IDENT, l0) :: _ =>
      Keyword.all.get(id.name) match
        case S(kw) =>
          consume
          rule.kwAlts.get(kw.name) match
            case S(subRule) =>
              yeetSpaces match
                case (tok @ BRACKETS(Indent, toks), loc) :: _ if subRule.blkAlt.isEmpty =>
                  consume
                  rec(toks, S(tok.innerLoc), tok.describe).concludeWith(_.blockOf(subRule))
                case _ =>
                  parse(subRule).getOrElse(errExpr) :: blockContOf(rule)
            case N =>
              // err((msg"Expected a keyword; found ${id.name} instead" -> S(l0) :: Nil))
              // errExpr
              ???
        case N =>
          tryParseExp(id, l0, rule).getOrElse(errExpr) :: blockContOf(rule)
    case (tok, loc) :: _ =>
      tryParseExp(tok, loc, rule).getOrElse(errExpr) :: blockContOf(rule)
  
  def blockContOf(rule: ParseRule[Tree]): Ls[Tree] =
    yeetSpaces match
      case (SEMI, _) :: _ => consume; blockOf(rule)
      case (NEWLINE, _) :: _ => consume; blockOf(rule)
      case _ => Nil
  
  private def tryParseExp[A](tok: Token, loc: Loc, rule: ParseRule[A]): Opt[A] =
    rule.exprAlt match
      case S(exprAlt) =>
        val e = expr
        parse(exprAlt.rest).map(res => exprAlt.k(e, res))
      case N =>
        err((msg"Expected ${rule.whatComesAfter} after ${rule.name}; found ${tok.describe} instead" -> S(loc) :: Nil))
        N
  def parse[A](rule: ParseRule[A]): Opt[A] = yeetSpaces match
    case (tok @ (id: IDENT), loc) :: _ =>
      Keyword.all.get(id.name) match
        case S(kw) =>
          consume
          rule.kwAlts.get(id.name) match
            case S(subRule) =>
              parse(subRule)
            case N =>
              // TODO(kw.name)
              err((msg"Expected ${rule.whatComesAfter} after ${rule.name}; found ${tok.describe} instead" -> S(loc) :: Nil))
              N
        case N =>
          // rule.exprAlt match
          //   case S(exprAlt) =>
          //     val e = expr
          //     parse(exprAlt.rest).map(res => exprAlt.k(e, res))
          //   case N =>
          //     err((msg"Expected ${rule.whatComesAfter} after ${rule.name}; found ${tok.describe} instead" -> S(loc) :: Nil))
          //     N
          tryParseExp(tok, loc, rule)
    case (tok @ (NEWLINE | SEMI), l0) :: _ =>
      // TODO(cur)
      rule.emptyAlt match
        case S(res) => S(res)
        case N =>
          err((msg"Expected ${rule.whatComesAfter} after ${rule.name}; found ${tok.describe} instead" -> lastLoc :: Nil))
          N
    case (tok @ BRACKETS(Indent, toks), loc) :: _ =>
      // rule.blkAlt match
      //   case S(res) => S(res)
      //   case N =>
      //     err((msg"Expected ${rule.whatComesAfter} after ${rule.name}; found ${tok.describe} instead" -> lastLoc :: Nil))
      //     N
      consume
      rule.blkAlt match
        case S(exprAlt) =>
          val e = rec(toks, S(tok.innerLoc), tok.describe).concludeWith(_.block)
            |> Tree.Block.apply
          parse(exprAlt.rest).map(res => exprAlt.k(e, res))
        case N =>
          err((msg"Expected ${rule.whatComesAfter} after ${rule.name}; found ${tok.describe} instead" -> S(loc) :: Nil))
          N
    case (tok, loc) :: _ =>
      tryParseExp(tok, loc, rule)
      // TODO(tok)
    case Nil =>
      rule.emptyAlt match
        case S(res) =>
          S(res)
        case N =>
          err((msg"Expected ${rule.whatComesAfter} after ${rule.name}; found end of input instead" -> lastLoc :: Nil))
          N
  
  def expr: Tree = cur match
    case (IDENT(nme, sym), loc) :: _ =>
      consume
      Tree.Ident(nme)
      // TODO cont
    case (LITVAL(lit), loc) :: _ =>
      consume
      lit.asTree
      // TODO cont
    case (BRACKETS(Indent, _), loc) :: _ =>
      err((msg"Expected an expression; found indented block instead" -> lastLoc :: Nil))
      errExpr
    case (tok, loc) :: _ =>
      TODO(tok)
    case Nil =>
      err((msg"Expected an expression; found end of input instead" -> lastLoc :: Nil))
      errExpr
  

object Parser:
  import Keyword.*
  
  type TokLoc = (Stroken, Loc)

  val letBinding = let :: rec /* :: Var :: is Term */ :: Nil
  
  val mods = `abstract` :: constructor :: Nil
  


  



