package mlscript.mono

import mlscript.{App, Asc, Assign, Bind, Blk, Block, Bra, CaseOf, Lam, Let, Lit,
                 New, Rcd, Sel, Subs, Term, Test, Tup, With, Var}
import mlscript.CaseBranches

object Helpers:
  def toFuncParams(term: Term): IterableOnce[Expr.Ref] = term match
    case Tup(fields) => fields.iterator.map {
      case (_, (Var(name), _)) => Expr.Ref(name)
      case _ => throw new MonomorphError("only support Var can be parameters")
    }
    case _ => throw MonomorphError("expect the list of parameters to be a Tup")
  
  def toFuncArgs(term: Term): IterableOnce[Term] = term match
    case Tup(fields) => fields.iterator.map(_._2._1)
    case _ => Some(term)

  /**
   * Check whether a term is static.
   */
  def isStatic(term: Term): Boolean =
    def go(term: Term)(using staticNames: Set[String]): Boolean =
      term match
        case With(trm, Rcd(fields)) =>
          go(trm) && fields.forall { case (_, (term, _)) => go(term) }
        case Rcd(fields) => fields.forall { case (_, (term, _)) => go(term) }
        case Tup(fields) => fields.forall { case (_, (term, _)) => go(term) }
        case Test(trm, _) => go(trm)
        // Should we regard typing units as non-static?
        case Block(typingUnit) => false
        case Assign(lhs, rhs) => go(lhs) && go(rhs)
        case Subs(arr, idx) => go(arr) && go(idx)
        case New(head, body) => false
        case CaseOf(trm, cases) => go(trm) && goCaseBranch(cases)
        case Bind(lhs, rhs) => go(lhs) && go(rhs)
        case Sel(receiver, _) => go(receiver)
        // If a lambda captures nothing, we regard it as static.
        case Lam(Tup(fields), rhs) =>
          // Collect parameter names.
          val names = fields.map {
            case (_, (Var(name), _)) => name
            case _ => throw new MonomorphError("currently only supports `Var` as parameters")
          }
          go(rhs)(using staticNames ++ names)
        case Lam(_, rhs) =>
          throw MonomorphError("the first argument of `Lam` should be `Tup`")
        case App(lhs, rhs) => go(lhs) && go(rhs)
        case Blk(stmts) => stmts.forall {
          case term: Term => go(term)
          case _ => false
        }
        case Let(isRec, Var(name), rhs, body) =>
          go(rhs) && go(body)(using staticNames + name)
        case Asc(trm, _) => go(trm)
        case Bra(_, trm) => go(trm)
        case _: Lit => true
        case Var(name) => staticNames contains name
    def goCaseBranch(branch: CaseBranches)(using staticNames: Set[String]): Boolean =
      import mlscript.{Case, Wildcard, NoCases}
      branch match {
        case Case(_, body, rest) => go(body) && goCaseBranch(rest)
        case Wildcard(body) => go(body)
        case NoCases => true
      }
    go(term)(using Set[String]())