package com.anthropic.eclipse.claude.preferences;

import org.eclipse.jface.preference.FieldEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Text;

/**
 * A password-style {@link FieldEditor} that reads and writes via
 * {@link SecureApiKeyStore} rather than the plain {@code IPreferenceStore}.
 *
 * <ul>
 *   <li>Characters are masked (SWT.PASSWORD).</li>
 *   <li>Value is never written to the workspace preference store.</li>
 * </ul>
 */
public class SecureStringFieldEditor extends FieldEditor {

    private Text text;

    public SecureStringFieldEditor(String name, String labelText, Composite parent) {
        super(name, labelText, parent);
    }

    // ==================== FieldEditor overrides ====================

    @Override
    protected void adjustForNumColumns(int numColumns) {
        if (text != null) {
            ((GridData) text.getLayoutData()).horizontalSpan = numColumns - 1;
        }
    }

    @Override
    protected void doFillIntoGrid(Composite parent, int numColumns) {
        getLabelControl(parent);

        text = new Text(parent, SWT.SINGLE | SWT.BORDER | SWT.PASSWORD);
        GridData gd = new GridData(GridData.FILL_HORIZONTAL);
        gd.horizontalSpan = numColumns - 1;
        text.setLayoutData(gd);
    }

    @Override
    public int getNumberOfControls() {
        return 2;
    }

    @Override
    protected void doLoad() {
        if (text != null) {
            text.setText(SecureApiKeyStore.getApiKey());
        }
    }

    @Override
    protected void doLoadDefault() {
        if (text != null) {
            text.setText("");
        }
    }

    @Override
    protected void doStore() {
        if (text != null) {
            SecureApiKeyStore.setApiKey(text.getText());
        }
    }
}
