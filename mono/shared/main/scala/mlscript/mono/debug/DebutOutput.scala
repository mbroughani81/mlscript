package mlscript.mono.debug

import scala.collection.mutable.ArrayBuffer
import RainbowDebug.black

enum DebugOutput extends Printable:
  case Code(lines: List[String])
  case Map(entries: List[(String, String)])
  case Plain(content: String)

  def getDebugOutput: DebugOutput = this

  import DebugOutput._

  def toLines: List[String] = this match
    case Code(lines) => if lines.length == 1 then lines else box(lines)
    case Map(entries) => boxMap(entries)
    case Plain(content) => content.linesIterator.toList

object DebugOutput:
  def box(lines: List[String]): List[String] =
    val maxWidth = lines.iterator.map(_.length).max
    val gutterWidth = if lines.length == 1 then 1 else scala.math.log10(lines.length).ceil.toInt
    val newLines = ArrayBuffer[String]()
    newLines += "┌" + "─" * (gutterWidth + 2) + "┬" + "─" * (maxWidth + 2) + "┐"
    lines.iterator.zipWithIndex.foreach { (line, index) =>
      newLines += ("│ " + (index + 1).toString + " │ " + black(line.padTo(maxWidth, ' ')) + " │")
    }
    newLines += "└" + "─" * (gutterWidth + 2) + "┴" + "─" * (maxWidth + 2) + "┘"
    newLines.toList

  private val KEY_TEXT = "(key)"
  private val VALUE_TEXT = "(value)"

  def boxMap(entries: List[(String, String)]): List[String] =
    val keyMaxWidth = entries.iterator.map(_._1.length).max.max(KEY_TEXT.length)
    val valueMaxWidth = entries.iterator.map(_._2.length).max.max(VALUE_TEXT.length)
    val newLines = ArrayBuffer[String]()
    newLines += "┌" + "─" * (keyMaxWidth + 2) + "┬" + "─" * (valueMaxWidth + 2) + "┐"
    newLines += ("│ " + KEY_TEXT.padTo(keyMaxWidth, ' ') + " │ " + VALUE_TEXT.padTo(valueMaxWidth, ' ') + " │")
    newLines += "├" + "─" * (keyMaxWidth + 2) + "┼" + "─" * (valueMaxWidth + 2) + "┤"
    entries.foreach { (key, value) =>
      val reprKey = key.padTo(keyMaxWidth, ' ')
      val reprValue = black(value.padTo(valueMaxWidth, ' '))
      newLines += ("│ " + reprKey + " │ " + reprValue + " │")
    }
    newLines += "└" + "─" * (keyMaxWidth + 2) + "┴" + "─" * (valueMaxWidth + 2) + "┘"
    newLines.toList