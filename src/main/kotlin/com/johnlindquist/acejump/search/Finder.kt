package com.johnlindquist.acejump.search

import com.intellij.find.FindModel
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea.EXACT_RANGE
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.johnlindquist.acejump.control.Handler
import com.johnlindquist.acejump.control.Trigger
import com.johnlindquist.acejump.label.Pattern
import com.johnlindquist.acejump.label.Tagger
import com.johnlindquist.acejump.view.Marker
import com.johnlindquist.acejump.view.Model.editor
import com.johnlindquist.acejump.view.Model.editorText
import com.johnlindquist.acejump.view.Model.markup
import com.johnlindquist.acejump.view.Model.viewBounds
import kotlin.system.measureTimeMillis
import kotlin.text.RegexOption.MULTILINE

/**
 * Singleton that searches for text in editor and highlights matching results.
 *
 * @see Tagger
 */

object Finder {
  private var results = hashSetOf<Int>()
  private var textHighlights = listOf<RangeHighlighter>()
  private var viewHighlights = listOf<RangeHighlighter>()
  private var model = FindModel()
  private var TEXT_HIGHLIGHT_LAYER = HighlighterLayer.LAST + 1
  private val logger = Logger.getInstance(Finder::class.java)

  val isShiftSelectEnabled
    get() = model.stringToFind.last().isUpperCase()


  var skim = false

  var query: String = ""
    set(value) {
      logger.info("Searching for locations matching: \"$value\"")
      field = value.toLowerCase()

      when {
        value.isEmpty() -> return
        Tagger.regex -> search()
        value.length == 1 -> skim()
        value.isValidQuery() -> skim()
        else -> field = field.dropLast(1)
      }
    }

  private fun skim() {
    logger.info("Skimming document for matches...")
    skim = true
    search(FindModel().apply { stringToFind = query })
    Trigger(400L) { if (skim) runLater { skim = false; search() } }
  }

  fun search(string: String = query) =
    search(model.apply { stringToFind = string })

  fun search(pattern: Pattern) {
    reset()
    search(FindModel().apply {
      stringToFind = pattern.string
      isRegularExpressions = true
      Tagger.reset()
    })
  }

  fun search(findModel: FindModel) {
    model = findModel

    val timeElapsed = measureTimeMillis {
      results = editorText.findMatchingSites().toHashSet()
    }

    logger.info("Discovered ${results.size} matches in $timeElapsed ms")

    if (!Tagger.hasTagSuffixInView(query)) highlightResults()
    if (!skim) tag(results)
  }

  private fun highlightResults() {
    if (results.size < 26) skim = false
    if (Tagger.regex) return
    paintTextHighlights()
  }

  fun paintTextHighlights() {
    val tempHighlights = results.map { createTextHighlighter(it) }
    textHighlights.forEach { markup.removeHighlighter(it) }
    textHighlights = tempHighlights
    viewHighlights = textHighlights.filter { it.startOffset in viewBounds }
  }

  private fun createTextHighlighter(it: Int) =
    markup.addRangeHighlighter(it,
      if (model.isRegularExpressions) it + 1 else it + query.length,
      TEXT_HIGHLIGHT_LAYER, null, EXACT_RANGE)
      .apply { customRenderer = Marker(query, null, this.startOffset) }

  private fun tag(results: Set<Int>) {
    Tagger.markOrJump(model, results)
    viewHighlights = viewHighlights.narrowBy { Tagger canDiscard startOffset }
      .also { newHighlights ->
        val numDiscarded = viewHighlights.size - newHighlights.size
        if (numDiscarded != 0) logger.info("Discarded $numDiscarded highlights")
      }

    Handler.paintTagMarkers()
  }

  fun List<RangeHighlighter>.narrowBy(cond: RangeHighlighter.() -> Boolean) =
    filter {
      if (cond(it)) {
        markup.removeHighlighter(it)
        false
      } else true
    }

  /**
   * Returns a list of indices where the query begins, within the given range.
   * These are full indices, ie. are not offset to the beginning of the range.
   */

  private fun String.findMatchingSites(key: String = query.toLowerCase(),
                                       cache: Set<Int> = results) =
    // If the cache is populated, filter it instead of redoing extra work
    if (cache.isEmpty()) findAll(Regex.escape(model.stringToFind))
    else cache.asSequence().filter { regionMatches(it, key, 0, key.length) }

  private fun Set<Int>.isCacheValidForRange() =
    viewBounds.let { view ->
      first() < view.first && last() > view.last
    }

  private fun CharSequence.findAll(key: String, startingFrom: Int = 0) =
    generateSequence({ Regex(key, MULTILINE).find(this, startingFrom) },
      Finder::filterNextResult).map { it.range.first }

  private tailrec fun filterNextResult(result: MatchResult): MatchResult? {
    val next = result.next()
    return if (next == null) null
    else if (editor.isVisible(next.range.first)) next
    else filterNextResult(next)
  }

  private fun String.isValidQuery() =
    results.any { editorText.regionMatches(it, this, 0, length) } ||
      Tagger.hasTagSuffixInView(query)

  fun reset() {
    markup.removeAllHighlighters()
    query = ""
    model = FindModel()
    results = hashSetOf()
    textHighlights = listOf()
    viewHighlights = listOf()
  }
}

