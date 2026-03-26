package com.anthropic.eclipse.claude.preferences;

import org.eclipse.jface.preference.FieldEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

/**
 * An editable combo field editor — shows preset values in a dropdown
 * but also allows the user to type a custom model name.
 * Similar to IntelliJ's editable ComboBox for model selection.
 */
public class EditableComboFieldEditor extends FieldEditor {

    private Combo combo;
    private String[] presets;

    public EditableComboFieldEditor(String name, String labelText, String[] presets, Composite parent) {
        this.presets = presets;
        init(name, labelText);
        createControl(parent);
    }

    @Override
    protected void adjustForNumColumns(int numColumns) {
        GridData gd = (GridData) combo.getLayoutData();
        gd.horizontalSpan = numColumns - 1;
    }

    @Override
    protected void doFillIntoGrid(Composite parent, int numColumns) {
        Label label = getLabelControl(parent);
        GridData labelData = new GridData();
        label.setLayoutData(labelData);

        combo = new Combo(parent, SWT.BORDER);
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd.horizontalSpan = numColumns - 1;
        combo.setLayoutData(gd);

        for (String preset : presets) {
            combo.add(preset);
        }

        combo.setToolTipText("Select a model or type a custom model name.");
    }

    @Override
    protected void doLoad() {
        if (combo != null) {
            String value = getPreferenceStore().getString(getPreferenceName());
            combo.setText(value);
        }
    }

    @Override
    protected void doLoadDefault() {
        if (combo != null) {
            String value = getPreferenceStore().getDefaultString(getPreferenceName());
            combo.setText(value);
        }
    }

    @Override
    protected void doStore() {
        getPreferenceStore().setValue(getPreferenceName(), combo.getText().trim());
    }

    @Override
    public int getNumberOfControls() {
        return 2;
    }
}
