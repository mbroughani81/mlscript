package mlscript.ucs

import mlscript.{Loc, SimpleTerm, Term, Var}
import mlscript.utils.lastWords
import mlscript.utils.shorthands._

// The point is to remember where the scrutinee comes from.
// Is it from nested patterns? Or is it from a `IfBody`?
final case class Scrutinee(local: Opt[Var], term: Term)(val matchRootLoc: Opt[Loc]) {
  def reference: SimpleTerm = local.getOrElse(term match {
    case term: SimpleTerm => term
    case _ => lastWords("`term` must be a `SimpleTerm` when `local` is empty")
  })

  override def toString: String =
    (local match {
      case N => ""
      case S(Var(alias)) => s"$alias @ "
    }) + s"$term"
}
