/*******************************************************************************
 *    sora-editor - the awesome code editor for Android
 *    https://github.com/Rosemoe/sora-editor
 *    Copyright (C) 2020-2024  Rosemoe
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 *
 *     This library is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *     Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public
 *     License along with this library; if not, write to the Free Software
 *     Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 *     USA
 *
 *     Please contact Rosemoe by email 2073412493@qq.com if you need
 *     additional information or have any questions
 *
 *     16 September 2024 - Modified by MohammedKHC
 ******************************************************************************/

package io.github.rosemoe.sora.lsp.editor.signature

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.View.MeasureSpec
import android.view.View.TEXT_DIRECTION_LTR
import android.widget.ScrollView
import android.widget.TextView
import io.github.rosemoe.sora.event.ColorSchemeUpdateEvent
import io.github.rosemoe.sora.event.EditorFocusChangeEvent
import io.github.rosemoe.sora.event.ScrollEvent
import io.github.rosemoe.sora.event.subscribeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.base.EditorPopupWindow
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import io.github.rosemoe.sora.widget.component.EditorDiagnosticTooltipWindow
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import io.noties.markwon.Markwon
import io.noties.markwon.syntax.Prism4jTheme
import io.noties.markwon.syntax.Prism4jThemeDarkula
import io.noties.markwon.syntax.Prism4jThemeDefault
import io.noties.markwon.syntax.SyntaxHighlightPlugin
import io.noties.prism4j.GrammarLocator
import io.noties.prism4j.Prism4j
import io.noties.prism4j.Prism4j.pattern
import io.noties.prism4j.Prism4j.token
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureInformation
import java.util.regex.Pattern
import kotlin.math.roundToInt

open class SignatureHelpWindow(editor: CodeEditor) : EditorPopupWindow(
    editor,
    FEATURE_HIDE_WHEN_FAST_SCROLL or FEATURE_SCROLL_AS_CONTENT
) {

    private var signatureBackgroundColor = 0
    private var highlightParameter = 0
    private var defaultTextColor = 0
    private val rootView: View = LayoutInflater.from(editor.context)
        .inflate(io.github.rosemoe.sora.lsp.R.layout.signature_help_tooltip_window, null, false)

    // editor width can change
    //private val maxWidth = (editor.width * 0.67).toInt()
    private val maxHeight = (editor.dpUnit * 235).toInt()

    private val text =
        rootView.findViewById<TextView>(io.github.rosemoe.sora.lsp.R.id.signature_help_tooltip_text).apply {
            movementMethod = LinkMovementMethod.getInstance()
            textDirection = TEXT_DIRECTION_LTR
        }
    private val locationBuffer = IntArray(2)
    protected val eventManager = editor.createSubEventManager()

    private lateinit var signatureHelp: SignatureHelp

    var markwonTheme =
        if (editor.colorScheme.isDark) Prism4jThemeDarkula.create()
        else Prism4jThemeDefault.create()

    private var markwon = Markwon
        .builder(editor.context)
        .usePlugin(SyntaxHighlightPlugin.create(Prism4j(object : GrammarLocator {
            override fun grammar(prism4j: Prism4j, language: String): Prism4j.Grammar? {
                return if (language == "rust") {
                    val stringToken = token(
                        "string",
                        pattern(
                            Pattern.compile("b?\"(?:\\\\[\\s\\S]|[^\\\\\"])*\"|b?r(#*)\"(?:[^\"]|\"(?!\\1))*\"\\1"),
                            false, true
                        ),
                    )
                    Prism4j.grammar(
                        "rust",

                        token(
                            "comment",
                            pattern(
                                Pattern.compile("(^|[^\\\\])/\\*(?:[^*/]|\\*(?!/)|/(?!\\*)|/\\*(?:[^*/]|\\*(?!/)|/(?!\\*)|/\\*(?:[^*/]|\\*(?!/)|/(?!\\*)|/\\*(?:[^*/]|\\*(?!/)|/(?!\\*)|[^\\s\\S])*\\*/)*\\*/)*\\*/)*\\*/"),
                                true, true
                            ),
                            pattern(
                                Pattern.compile("(^|[^\\\\:])//.*"),
                                true, true
                            ),
                        ),
                        stringToken,
                        token(
                            "char",
                            pattern(
                                Pattern.compile("b?'(?:\\\\(?:x[0-7][\\da-fA-F]|u\\{(?:[\\da-fA-F]_*){1,6}\\}|.)|[^\\\\\\r\\n\\t'])'"),
                                false, true
                            ),
                        ),
                        token(
                            "attribute",
                            pattern(
                                Pattern.compile("#!?\\[(?:[^\\[\\]\"]|\"(?:\\\\[\\s\\S]|[^\\\\\"])*\")*\\\\]"),
                                false, true, "attr-name",
                                Prism4j.grammar("inside", stringToken)
                            ),
                        ),
                        token(
                            "closure-params",
                            pattern(
                                Pattern.compile("([=(,:]\\s*|\\bmove\\s*)\\|[^|]*\\||\\|[^|]*\\|(?=\\s*(?:\\{|->))"),
                                true, true, null,
                                Prism4j.grammar(
                                    "inside",
                                    token(
                                        "closure-punctuation",
                                        pattern(
                                            Pattern.compile("^\\||\\|$"),
                                            false,
                                            false,
                                            "punctuation"
                                        )
                                    )
                                )
                            ),
                        ),
                        token(
                            "lifetime-annotation",
                            pattern(
                                Pattern.compile("'\\w+"),
                                false, false, "symbol"
                            ),
                        ),
                        token(
                            "fragment-specifier",
                            pattern(
                                Pattern.compile("(\\$\\w+:)[a-z]+"),
                                true, false, "punctuation"
                            ),
                        ),
                        token(
                            "variable",
                            pattern(
                                Pattern.compile("\\$\\w+")
                            ),
                        ),
                        token(
                            "function-definition",
                            pattern(
                                Pattern.compile("(\\bfn\\s+)\\w+"),
                                true, false, "function"
                            ),
                        ),
                        token(
                            "type-definition",
                            pattern(
                                Pattern.compile("(\\b(?:enum|struct|trait|type|union)\\s+)\\w+"),
                                true, false, "class-name"
                            ),
                        ),
                        token(
                            "module-declaration",
                            pattern(
                                Pattern.compile("(\\b(?:crate|mod)\\s+)[a-z][a-z_\\d]*"),
                                true, false, "namespace"
                            ),
                            pattern(
                                Pattern.compile("(\\b(?:crate|self|super)\\s*)::\\s*[a-z][a-z_\\d]*\\b(?:\\s*::(?:\\s*[a-z][a-z_\\d]*\\s*::)*)?"),
                                true, false, "namespace",
                                Prism4j.grammar(
                                    "inside",
                                    token("punctuation", pattern(Pattern.compile("::")))
                                )
                            ),
                        ),
                        token(
                            "keyword",
                            pattern(
                                Pattern.compile("\\b(?:Self|abstract|as|async|await|become|box|break|const|continue|crate|do|dyn|else|enum|extern|final|fn|for|if|impl|in|let|loop|macro|match|mod|move|mut|override|priv|pub|ref|return|self|static|struct|super|trait|try|type|typeof|union|unsafe|unsized|use|virtual|where|while|yield)\\b")
                            ),
                            pattern(
                                Pattern.compile("\\b(?:bool|char|f(?:32|64)|[ui](?:8|16|32|64|128|size)|str)\\b")
                            ),
                        ),
                        token(
                            "function",
                            pattern(
                                Pattern.compile("\\b[a-z_]\\w*(?=\\s*(?:::\\s*<|\\())")
                            ),
                        ),
                        token(
                            "macro",
                            pattern(
                                Pattern.compile("\\b\\w+!"),
                                false, false, "property"
                            ),
                        ),
                        token(
                            "constant",
                            pattern(
                                Pattern.compile("\\b[A-Z_][A-Z_\\d]+\\b")
                            ),
                        ),
                        token(
                            "class-name",
                            pattern(
                                Pattern.compile("\\b[A-Z]\\w*\\b")
                            ),
                        ),
                        token(
                            "namespace",
                            pattern(
                                Pattern.compile("(?:\\b[a-z][a-z_\\d]*\\s*::\\s*)*\\b[a-z][a-z_\\d]*\\s*::(?!\\s*<)"),
                                false, false
                            ),
                        ),
                        token(
                            "number",
                            pattern(
                                Pattern.compile("\\b(?:0x[\\dA-Fa-f](?:_?[\\dA-Fa-f])*|0o[0-7](?:_?[0-7])*|0b[01](?:_?[01])*|(?:(?:\\d(?:_?\\d)*)?\\.)?\\d(?:_?\\d)*(?:[Ee][+-]?\\d+)?)(?:_?(?:f32|f64|[iu](?:8|16|32|64|size)?))?\\b")
                            ),
                        ),
                        token(
                            "boolean",
                            pattern(
                                Pattern.compile("\\b(?:false|true)\\b")
                            ),
                        ),
                        token(
                            "punctuation",
                            pattern(
                                Pattern.compile("->|\\.\\.=|\\.{1,3}|::|[{}\\\\;(),:]"),
                                false,
                                false,
                                null,
                                Prism4j.grammar(
                                    "inside",
                                    token("punctuation", pattern(Pattern.compile("::")))
                                )
                            ),
                        ),
                        token(
                            "operator",
                            pattern(
                                Pattern.compile("[-+*/%!^]=?|=[=>]?|&[&=]?|\\|[|=]?|<<?=?|>>?=?|[@?]")
                            ),
                        ),


                        )
                } else null
            }

            override fun languages(): MutableSet<String> {
                return mutableSetOf("rust")
            }

        }), object : Prism4jTheme {
            override fun background(): Int {
                return markwonTheme.background()
            }

            override fun textColor(): Int {
                return markwonTheme.textColor()
            }
            override fun apply(
                language: String,
                syntax: Prism4j.Syntax,
                builder: SpannableStringBuilder,
                start: Int,
                end: Int
            ) {
                markwonTheme.apply(language, syntax, builder, start, end)
            }
        }))
        .build()

    init {
        popup.elevation = 0F
        super.setContentView(rootView)

        eventManager.subscribeEvent<EditorFocusChangeEvent> { e, _ ->
            if (!e.isGainFocus) dismiss()
        }

        eventManager.subscribeEvent<ScrollEvent> { _, _ ->
            if (editor.isInMouseMode) {
                return@subscribeEvent
            }
            if (isShowing) updateWindowSizeAndLocation()
        }

        eventManager.subscribeEvent<ColorSchemeUpdateEvent> { _, _ ->
            applyColorScheme()
        }

        applyColorScheme()
    }

    fun isEnabled() = eventManager.isEnabled

    fun setEnabled(enabled: Boolean) {
        eventManager.isEnabled = enabled
        if (!enabled) {
            dismiss()
        }
    }

    open fun show(signatureHelp: SignatureHelp) {
        this.signatureHelp = signatureHelp
        editor.getComponent(EditorAutoCompletion::class.java).dismiss()
        editor.getComponent(EditorDiagnosticTooltipWindow::class.java).dismiss()
        (text.parent as ScrollView).scrollTo(0, 0)
        renderSignatureHelp()
        if (text.text.trim().isEmpty()) {
            dismiss()
            return
        }
        updateWindowSizeAndLocation()
        show()
    }


    private fun updateWindowSizeAndLocation() {
        val messageWidth = (editor.width * 0.87).toInt()

        rootView.measure(
            MeasureSpec.makeMeasureSpec(messageWidth, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
        )

        val width = rootView.measuredWidth
        val height = rootView.measuredHeight

        setSize(width, height)

        updateWindowPosition()
    }

    protected open fun updateWindowPosition() {
        val selection = editor.cursor.left()
        val charX = editor.getCharOffsetX(selection.line, selection.column)
        val charY = editor.getCharOffsetY(
            selection.line,
            selection.column
        ) - editor.rowHeight - 10 * editor.dpUnit
        editor.getLocationInWindow(locationBuffer)
        val restAbove = charY + locationBuffer[1]
        val restBottom = editor.height - charY - editor.rowHeight
        val windowY = if (restAbove > restBottom) {
            charY - height
        } else {
            charY + editor.rowHeight * 1.5f
        }
        if (windowY < 0) {
            dismiss()
            return
        }
        val windowX = (charX - width / 2).coerceIn(0f, editor.width.toFloat() - width)
        setLocationAbsolutely(windowX.toInt(), windowY.toInt())
    }

    private fun renderSignatureHelp() {

        val activeSignatureIndex = signatureHelp.activeSignature
        val activeParameterIndex = signatureHelp.activeParameter
        val signatures = signatureHelp.signatures

        val renderStringBuilder = SpannableStringBuilder()

        if (activeSignatureIndex < 0 || activeParameterIndex < 0) {
            Log.d("SignatureHelpWindow", "activeSignature or activeParameter is negative")
            return
        }

        if (activeSignatureIndex >= signatures.size) {
            Log.d("SignatureHelpWindow", "activeSignature is out of range")
            return
        }

        // Get only the activated signature
        for (i in 0..activeSignatureIndex) {
            formatSignature(
                signatures[i],
                activeParameterIndex,
                renderStringBuilder,
                isCurrentSignature = i == activeSignatureIndex
            )
            if (i < activeSignatureIndex) {
                renderStringBuilder.append("\n")
            }
        }

        signatures[activeSignatureIndex].documentation?.let {
            renderStringBuilder.append("\n\n")
            renderStringBuilder.append(
                if (it.isLeft) it.left
                else markwon.toMarkdown(it.right.value)
            )
        }

        text.text = renderStringBuilder
    }

    private fun formatSignature(
        signature: SignatureInformation,
        activeParameterIndex: Int,
        renderStringBuilder: SpannableStringBuilder,
        isCurrentSignature: Boolean
    ) {
        val label = signature.label

        val parameters = signature.parameters
        val activeParameter = parameters.getOrNull(activeParameterIndex)

        val bracketIndex = label.indexOfAny(charArrayOf('(', '<'))
        if (bracketIndex < 0) return
        val parameterStart = label.substring(0, bracketIndex)
        val currentIndex = 0.coerceAtLeast(renderStringBuilder.lastIndex);

        renderStringBuilder.append(
            parameterStart,
            ForegroundColorSpan(defaultTextColor), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        renderStringBuilder.append(
            label[bracketIndex]
        )

        for (i in 0 until parameters.size) {
            val parameter = parameters[i]

            val text =
                if (parameter.label.isLeft) parameter.label.left
                else label.substring(parameter.label.right.first, parameter.label.right.second)

            if (parameter == activeParameter && isCurrentSignature) {
                renderStringBuilder.append(
                    text,
                    ForegroundColorSpan(highlightParameter),
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                renderStringBuilder.setSpan(
                    StyleSpan(Typeface.BOLD),
                    renderStringBuilder.lastIndex - text.length,
                    renderStringBuilder.lastIndex,
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                if (i != parameters.size - 1) {
                    renderStringBuilder.append(
                        ", ", ForegroundColorSpan(highlightParameter),
                        SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            } else {
                renderStringBuilder.append(
                    text,
                    ForegroundColorSpan(defaultTextColor),
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
                )

            }

            if (i != parameters.size - 1 && (!isCurrentSignature || parameter != activeParameter)) {
                renderStringBuilder.append(", ")
            }

        }

        renderStringBuilder.append(when (label[bracketIndex]) {
            '(' -> ")"
            '<' -> ">"
            else -> ""
        })

        if (isCurrentSignature) {
            renderStringBuilder.setSpan(
                StyleSpan(Typeface.BOLD),
                currentIndex,
                renderStringBuilder.lastIndex,
                SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

    }


    private fun applyColorScheme() {
        val colorScheme = editor.colorScheme
        text.typeface = editor.typefaceText
        defaultTextColor = colorScheme.getColor(EditorColorScheme.SIGNATURE_TEXT_NORMAL)

        highlightParameter =
            colorScheme.getColor(EditorColorScheme.SIGNATURE_TEXT_HIGHLIGHTED_PARAMETER)

        signatureBackgroundColor =
            colorScheme.getColor(EditorColorScheme.SIGNATURE_BACKGROUND)

        val background = GradientDrawable()
        background.cornerRadius = editor.dpUnit * 8
        background.setColor(colorScheme.getColor(EditorColorScheme.SIGNATURE_BACKGROUND))
        background.setStroke(editor.dpUnit.roundToInt(), colorScheme.getColor(EditorColorScheme.COMPLETION_WND_CORNER))
        rootView.background = background

        if (isShowing) {
            renderSignatureHelp()
        }
    }


}