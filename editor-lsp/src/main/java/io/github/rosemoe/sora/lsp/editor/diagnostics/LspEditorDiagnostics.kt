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
 ******************************************************************************/

package io.github.rosemoe.sora.lsp.editor.diagnostics

import android.content.Context
import io.github.rosemoe.sora.lang.completion.snippet.parser.CodeSnippetParser
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticDetail
import io.github.rosemoe.sora.lsp.editor.LspEditor
import io.github.rosemoe.sora.lsp.events.EventType
import io.github.rosemoe.sora.lsp.events.document.applyEdits
import io.github.rosemoe.sora.lsp.utils.FileUri
import io.github.rosemoe.sora.lsp.utils.toFileUri
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.component.EditorDiagnosticTooltipWindow
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import java.net.URI

class LspEditorDiagnostics(
    editor: CodeEditor,
    private val lspEditor: LspEditor,
    private val openEditorCallback: (context: Context, uri: FileUri, afterOpenCallback: (CodeEditor, LspEditor) -> Unit) -> Unit
) :
    EditorDiagnosticTooltipWindow(editor) {
    fun updateDiagnostics() {
        editor.postInLifecycle {
            super.updateDiagnostic(editor.cursor.left())
        }
    }

    fun applyCodeActionEdits(edit: WorkspaceEdit) {
        edit.changes?.forEach { change ->
            applyCodeActionEdits(change.value)
        }

        edit.documentChanges?.forEach { change ->
            if (change.isLeft) {
                val uri = URI(change.left.textDocument.uri).toFileUri()
                if (lspEditor.uri == uri) {
                    applyCodeActionEdits(change.left.edits)
                } else {
                    openEditorCallback(editor.context, uri) { editor, lspEditor ->
                        applyCodeActionEdits(change.left.edits, editor, lspEditor)
                    }
                }
            }
        }
    }

    fun applyCodeActionEdits(
        textEdits: List<TextEdit>,
        currentEditor: CodeEditor = editor,
        currentLspEditor: LspEditor = lspEditor
    ) {
        if (textEdits.isNotEmpty() && textEdits.any { it.newText.contains("$") }) {
            // TODO: Maybe find better way
            textEdits.reversed().forEach { textEdit ->
                val codeSnippet = CodeSnippetParser.parse(textEdit.newText)
                val startIndex =
                    currentEditor.text.getCharIndex(
                        textEdit.range.start.line,
                        textEdit.range.start.character
                    )
                val endIndex =
                    currentEditor.text.getCharIndex(
                        textEdit.range.end.line,
                        textEdit.range.end.character
                    )
                val selectedText = currentEditor.text.subSequence(startIndex, endIndex).toString()
                currentEditor.text.delete(startIndex, endIndex)

                currentEditor.snippetController
                    .startSnippet(startIndex, codeSnippet, selectedText)
            }
        } else {
            currentLspEditor.eventManager.emit(EventType.applyEdits) {
                put("edits", textEdits)
                put(currentEditor.text)
            }
        }
    }

    var setDiagnosticCallback: ((DiagnosticDetail?) -> Unit)? = null

    override fun updateWindowSize() {}

    override fun updateDiagnostic(diagnostic: DiagnosticDetail?, position: CharPosition?) {
        if (isEnabled && diagnostic != currentDiagnostic) {
            setDiagnosticCallback?.invoke(diagnostic)
        }
        super.updateDiagnostic(diagnostic, position)
    }

    override fun updateWindowPosition() {
        if (lspEditor.isShowSignatureHelp) {
            dismiss()
            return
        }

        // FIXME Sometimes we get java.lang.IndexOutOfBoundsException when deleting.
        runCatching {
            super.updateWindowPosition()
        }
    }

    override fun show() {
        if (lspEditor.isShowSignatureHelp) {
            dismiss()
            return
        }
        super.show()
    }
}