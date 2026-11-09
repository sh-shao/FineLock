package finelock.intellij;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffDialogHints;
import com.intellij.diff.DiffManager;
import com.intellij.diff.chains.SimpleDiffRequestChain;
import com.intellij.diff.requests.DiffRequest;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import finelock.core.RefactoringResult;
import finelock.core.SectionReport;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Shows the inferred lock for every section; lets the user look at the diff before applying. */
final class FineLockResultDialog extends DialogWrapper {

    private static final String[] COLUMNS = {"Location", "Member", "Monitor", "Pattern", "Lock", "Note"};

    private final Project project;
    private final RefactoringResult result;
    private final JBTable table;

    FineLockResultDialog(Project project, RefactoringResult result) {
        super(project, true);
        this.project = project;
        this.result = result;
        List<SectionReport> rows = result.sections();
        table = new JBTable(new AbstractTableModel() {
            @Override
            public int getRowCount() {
                return rows.size();
            }

            @Override
            public int getColumnCount() {
                return COLUMNS.length;
            }

            @Override
            public String getColumnName(int c) {
                return COLUMNS[c];
            }

            @Override
            public Object getValueAt(int r, int c) {
                SectionReport s = rows.get(r);
                return switch (c) {
                    case 0 -> s.file().getFileName() + ":" + s.line();
                    case 1 -> s.member();
                    case 2 -> s.monitor();
                    case 3 -> s.pattern() == null ? "" : s.pattern();
                    case 4 -> !s.refactored() ? "unchanged" : s.mode() == null ? "merged" : s.mode().description();
                    default -> s.note() == null ? "" : s.note();
                };
            }
        });
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && table.getSelectedRow() >= 0) {
                    SectionReport s = rows.get(table.convertRowIndexToModel(table.getSelectedRow()));
                    VirtualFile vf = LocalFileSystem.getInstance().findFileByNioFile(s.file());
                    if (vf != null) new OpenFileDescriptor(project, vf, Math.max(0, s.line() - 1), 0).navigate(true);
                }
            }
        });
        table.getColumnModel().getColumn(0).setPreferredWidth(160);
        table.getColumnModel().getColumn(1).setPreferredWidth(260);
        table.getColumnModel().getColumn(2).setPreferredWidth(140);
        table.getColumnModel().getColumn(3).setPreferredWidth(90);
        table.getColumnModel().getColumn(4).setPreferredWidth(190);
        table.getColumnModel().getColumn(5).setPreferredWidth(360);
        setTitle("FineLock: Inferred Locks");
        setOKButtonText(result.changedFiles().isEmpty() ? "Close" : "Apply");
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        RefactoringResult.Summary s = result.summary();
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        panel.add(new JBLabel(String.format(
                "<html>%d synchronized lock(s): <b>%d downgrading</b>, <b>%d splitting</b>, %d read, %d write, %d merged, "
                        + "%d unchanged &mdash; %d file(s) would change. Double-click a row to open the source.</html>",
                s.synchronizedLocks(), s.downgrading(), s.splitting(), s.read(), s.write(), s.merged(), s.notRefactored(),
                result.changedFiles().size())), BorderLayout.NORTH);
        JBScrollPane scroll = new JBScrollPane(table);
        scroll.setPreferredSize(JBUI.size(1100, 420));
        panel.add(scroll, BorderLayout.CENTER);
        if (!result.warnings().isEmpty()) {
            panel.add(new JBLabel(result.warnings().size() + " warning(s); use \"Copy Report\" to see them."), BorderLayout.SOUTH);
        }
        return panel;
    }

    @Override
    protected Action @NotNull [] createLeftSideActions() {
        Action diff = new AbstractAction("Show Diff") {
            @Override
            public void actionPerformed(ActionEvent e) {
                showDiff();
            }
        };
        diff.setEnabled(!result.changedFiles().isEmpty());
        Action copy = new AbstractAction("Copy Report") {
            @Override
            public void actionPerformed(ActionEvent e) {
                CopyPasteManager.getInstance().setContents(new StringSelection(result.toMarkdown()));
            }
        };
        return new Action[]{diff, copy};
    }

    private void showDiff() {
        DiffContentFactory f = DiffContentFactory.getInstance();
        List<DiffRequest> requests = new ArrayList<>();
        for (Map.Entry<Path, String> e : result.changedFiles().entrySet()) {
            String before = StringUtil.convertLineSeparators(result.originalFiles().get(e.getKey()));
            String after = StringUtil.convertLineSeparators(e.getValue());
            requests.add(new SimpleDiffRequest(e.getKey().getFileName().toString(),
                    f.create(project, before, JavaFileType.INSTANCE), f.create(project, after, JavaFileType.INSTANCE),
                    "Original", "After FineLock"));
        }
        DiffManager.getInstance().showDiff(project, new SimpleDiffRequestChain(requests), DiffDialogHints.MODAL);
    }
}
