package finelock.intellij;

import com.intellij.notification.NotificationType;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import finelock.core.RefactoringResult;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Writes the refactored text into the editor documents as one undoable command. */
final class ApplyChanges {

    private ApplyChanges() {}

    /** Returns the number of files changed. Files edited since the analysis are skipped. */
    static int apply(Project project, RefactoringResult result) {
        List<String> skipped = new ArrayList<>();
        int[] changed = {0};
        WriteCommandAction.writeCommandAction(project)
                .withName("FineLock: Convert to Fine-Grained Locks")
                .withGlobalUndo()
                .run(() -> {
                    for (Map.Entry<Path, String> e : result.changedFiles().entrySet()) {
                        VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(e.getKey());
                        Document doc = vf == null ? null : FileDocumentManager.getInstance().getDocument(vf);
                        if (doc == null || !doc.isWritable()) {
                            skipped.add(e.getKey().getFileName().toString());
                            continue;
                        }
                        String original = StringUtil.convertLineSeparators(result.originalFiles().get(e.getKey()));
                        if (!doc.getText().equals(original)) {
                            skipped.add(e.getKey().getFileName() + " (changed since the analysis)");
                            continue;
                        }
                        doc.setText(StringUtil.convertLineSeparators(e.getValue()));
                        changed[0]++;
                    }
                    PsiDocumentManager.getInstance(project).commitAllDocuments();
                });
        if (!skipped.isEmpty()) {
            FineLockAction.notify(project, "FineLock skipped: " + String.join(", ", skipped), NotificationType.WARNING);
        }
        return changed[0];
    }
}
