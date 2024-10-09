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
import android.util.Log
import android.widget.Toast
import io.github.rosemoe.sora.lang.completion.snippet.parser.CodeSnippetParser
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticDetail
import io.github.rosemoe.sora.lang.diagnostic.Quickfix
import io.github.rosemoe.sora.lsp.editor.LspEditor
import io.github.rosemoe.sora.lsp.editor.getEventListener
import io.github.rosemoe.sora.lsp.events.EventType
import io.github.rosemoe.sora.lsp.events.document.DocumentChangeEvent
import io.github.rosemoe.sora.lsp.events.document.applyEdits
import io.github.rosemoe.sora.lsp.requests.Timeout
import io.github.rosemoe.sora.lsp.requests.Timeouts
import io.github.rosemoe.sora.lsp.utils.FileUri
import io.github.rosemoe.sora.lsp.utils.createTextDocumentIdentifier
import io.github.rosemoe.sora.lsp.utils.toFileUri
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.component.EditorDiagnosticTooltipWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.CodeActionTriggerKind
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import java.net.URI
import java.util.concurrent.TimeUnit

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

    override fun updateDiagnostic(diagnostic: DiagnosticDetail?, position: CharPosition?) {
        if (!isEnabled) return

        // Just in case
        if (isShowing) dismiss()

        if (diagnostic?.extraData != null && diagnostic.extraData is Diagnostic) {
            val quickfixes = mutableListOf<Quickfix>()
            val diagnosticSource = diagnostic.extraData as Diagnostic
            CoroutineScope(Dispatchers.IO).launch {
                val documentChangeEvent =
                    lspEditor.eventManager.getEventListener<DocumentChangeEvent>()

                val documentChangeFuture =
                    documentChangeEvent?.future

                if (documentChangeFuture?.isDone == false || documentChangeFuture?.isCompletedExceptionally == false || documentChangeFuture?.isCancelled == false) {
                    runCatching {
                        documentChangeFuture[Timeout[Timeouts.WILLSAVE].toLong(), TimeUnit.MILLISECONDS]
                    }
                }

                try {
                    val codeAction = lspEditor.requestManager?.codeAction(
                        CodeActionParams(
                            lspEditor.uri.createTextDocumentIdentifier(),
                            diagnosticSource.range,
                            CodeActionContext(
                                listOf(diagnosticSource),
                                listOf(CodeActionKind.QuickFix)
                            ).apply {
                                triggerKind = CodeActionTriggerKind.Automatic
                            }
                        )
                    )?.get(2, TimeUnit.SECONDS)

                    codeAction?.forEach {
                        if (it.isLeft) return@forEach
                        quickfixes += Quickfix(it.right.title, fixAction = {
                            if (it.right.edit != null) {
                                applyCodeActionEdits(it.right.edit)
                            } else {
                                lspEditor.coroutineScope.launch(Dispatchers.IO) {
                                    runCatching {
                                        lspEditor.requestManager!!.resolveCodeAction(it.right)
                                            ?.get(2, TimeUnit.SECONDS)?.let { newCodeAction ->
                                                withContext(Dispatchers.Main) {
                                                    applyCodeActionEdits(newCodeAction.edit)
                                                }
                                            }
                                    }.onFailure {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(
                                                editor.context,
                                                "Failed to apply code action.",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                }
                            }
                        })
                    }
                } catch (e: Throwable) {
                    Log.w("LspEditorDiagnostics", e)
                }

                if (quickfixes.size > 0) {
                    withContext(Dispatchers.Main) {
                        if (isShowing && currentDiagnostic == diagnostic) {
                            super.updateDiagnostic(
                                DiagnosticDetail(
                                    diagnostic.briefMessage,
                                    diagnostic.detailedMessage,
                                    quickfixes,
                                    null
                                ), editor.cursor.left()
                            )
                        }
                    }
                }
            }
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