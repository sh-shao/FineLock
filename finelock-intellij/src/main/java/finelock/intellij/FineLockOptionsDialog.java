package finelock.intellij;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import finelock.core.FineLockOptions;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;

/** Asks for the FineLock options; remembers them between runs. */
final class FineLockOptionsDialog extends DialogWrapper {

    private static final String PREFIX = "finelock.";
    private final JBCheckBox downgrade = new JBCheckBox("Infer downgrading locks (read -> write -> read)");
    private final JBCheckBox split = new JBCheckBox("Infer splitting locks");
    private final JBCheckBox fair = new JBCheckBox("Create fair locks (new ReentrantReadWriteLock(true))");
    private final String scope;

    FineLockOptionsDialog(Project project, String scope) {
        super(project);
        this.scope = scope;
        PropertiesComponent p = PropertiesComponent.getInstance();
        downgrade.setSelected(p.getBoolean(PREFIX + "downgrade", true));
        split.setSelected(p.getBoolean(PREFIX + "split", true));
        fair.setSelected(p.getBoolean(PREFIX + "fair", false));
        setTitle("FineLock: Convert to Fine-Grained Locks");
        setOKButtonText("Analyse");
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        return FormBuilder.createFormBuilder()
                .addComponent(new JBLabel("<html>Convert <code>synchronized</code> methods and blocks in <b>" + escape(scope)
                        + "</b><br>into <code>ReentrantReadWriteLock</code> locks.</html>"))
                .addVerticalGap(8)
                .addComponent(downgrade)
                .addComponent(split)
                .addComponent(fair)
                .getPanel();
    }

    FineLockOptions options() {
        PropertiesComponent p = PropertiesComponent.getInstance();
        p.setValue(PREFIX + "downgrade", downgrade.isSelected(), true);
        p.setValue(PREFIX + "split", split.isSelected(), true);
        p.setValue(PREFIX + "fair", fair.isSelected(), false);
        FineLockOptions o = new FineLockOptions();
        o.enableDowngrade = downgrade.isSelected();
        o.enableSplitting = split.isSelected();
        o.fairLocks = fair.isSelected();
        return o;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
