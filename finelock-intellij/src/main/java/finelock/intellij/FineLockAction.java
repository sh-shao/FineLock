package finelock.intellij;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.OrderEnumerator;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.JarFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import finelock.core.FineLock;
import finelock.core.FineLockOptions;
import finelock.core.RefactoringResult;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Refactor | Convert to Fine-Grained Locks (FineLock)... */
public final class FineLockAction extends AnAction {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        VirtualFile[] selected = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        List<Path> scopePaths = new ArrayList<>();
        if (selected != null) {
            for (VirtualFile f : selected) {
                if (f.isInLocalFileSystem() && (f.isDirectory() || "java".equals(f.getExtension()))) {
                    scopePaths.add(f.toNioPath().toAbsolutePath().normalize());
                }
            }
        }
        List<Path> roots = new ArrayList<>();
        for (VirtualFile r : ProjectRootManager.getInstance(project).getContentSourceRoots()) {
            if (r.isInLocalFileSystem()) roots.add(r.toNioPath());
        }
        if (roots.isEmpty()) {
            notify(project, "FineLock found no source roots in this project.", NotificationType.WARNING);
            return;
        }
        // whole project when nothing (or a content root above all source roots) is selected
        boolean wholeProject = scopePaths.isEmpty() || scopePaths.stream().anyMatch(p -> roots.stream().allMatch(r -> r.startsWith(p)));
        String scopeText = wholeProject ? "the whole project" : describe(scopePaths);

        FineLockOptionsDialog dialog = new FineLockOptionsDialog(project, scopeText);
        if (!dialog.showAndGet()) return;
        FineLockOptions options = dialog.options();

        List<Path> classpath = classpath(project);
        Predicate<Path> scope = wholeProject ? p -> true : p -> scopePaths.stream().anyMatch(p::startsWith);
        FileDocumentManager.getInstance().saveAllDocuments();

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "FineLock: analysing locks", true) {
            private RefactoringResult result;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                indicator.setText("Parsing sources and analysing side effects");
                try {
                    result = FineLock.refactor(roots, classpath, scope, options);
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }

            @Override
            public void onSuccess() {
                if (result.sections().isEmpty()) {
                    FineLockAction.notify(project, "FineLock found no synchronized methods or blocks in " + scopeText + ".",
                            NotificationType.INFORMATION);
                    return;
                }
                FineLockResultDialog rd = new FineLockResultDialog(project, result);
                if (rd.showAndGet()) {
                    int n = ApplyChanges.apply(project, result);
                    RefactoringResult.Summary s = result.summary();
                    FineLockAction.notify(project, String.format(
                            "Refactored %d of %d lock(s) in %d file(s): %d downgrading, %d splitting, %d read, %d write.",
                            s.synchronizedLocks() - s.notRefactored(), s.synchronizedLocks(), n, s.downgrading(),
                            s.splitting(), s.read(), s.write()), NotificationType.INFORMATION);
                }
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                FineLockAction.notify(project, "FineLock failed: " + cause.getMessage(), NotificationType.ERROR);
            }
        });
    }

    private static List<Path> classpath(Project project) {
        Set<Path> out = new LinkedHashSet<>();
        for (VirtualFile root : OrderEnumerator.orderEntries(project).withoutSdk().librariesOnly().classes().getRoots()) {
            VirtualFile local = JarFileSystem.getInstance().getVirtualFileForJar(root);
            if (local != null && local.isInLocalFileSystem()) out.add(local.toNioPath());
        }
        return new ArrayList<>(out);
    }

    private static String describe(List<Path> paths) {
        if (paths.size() == 1) return paths.get(0).getFileName().toString();
        return paths.size() + " selected items";
    }

    static void notify(Project project, String text, NotificationType type) {
        NotificationGroupManager.getInstance().getNotificationGroup("FineLock").createNotification(text, type).notify(project);
    }
}
