package org.syncany.gui.wizard;

import java.io.File;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.syncany.config.Config;
import org.syncany.gui.util.I18n;
import org.syncany.gui.util.SWTResourceManager;

/**
 * @author Vincent Wiencek <vwiencek@gmail.com>
 * @author Philipp C. Heckel <philipp.heckel@gmail.com>
 */
public class SelectFolderPanel extends Panel {
	public enum SelectFolderValidationMethod {
		NO_APP_FOLDER, APP_FOLDER
	};

	private Text localDir;
	private Label descriptionText;
	private Label messageLabel;

	private SelectFolderValidationMethod validationMethod;

	public SelectFolderPanel(WizardDialog wizardParentDialog, Composite parent, int style) {
		super(wizardParentDialog, parent, style);
				
		// Main composite
		GridLayout mainCompositeGridLayout = new GridLayout(2, false);
		mainCompositeGridLayout.marginTop = 15;
		mainCompositeGridLayout.marginLeft = 10;
		mainCompositeGridLayout.marginRight = 20;

		setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 1, 1));		
		setLayout(mainCompositeGridLayout);
		setBackground(SWTResourceManager.getColor(236, 236, 236));
	
		// Title and welcome text
		Label titleLabel = new Label(this, SWT.WRAP);
		titleLabel.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, true, false, 4, 1));
		titleLabel.setText(I18n.getString("dialog.selectLocalFolder.introduction.title"));
			
		WidgetDecorator.title(titleLabel);

		descriptionText = new Label(this, SWT.WRAP);
		descriptionText.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, true, false, 3, 1));
		descriptionText.setText("..");

		WidgetDecorator.normal(descriptionText);

		// Label "Folder:"
		GridData selectFolderLabel = new GridData(SWT.LEFT, SWT.CENTER, false, false, 1, 1);
		selectFolderLabel.verticalIndent = WidgetDecorator.VERTICAL_INDENT;
		selectFolderLabel.horizontalSpan = 2;

		Label seledctFolderLabel = new Label(this, SWT.WRAP);
		seledctFolderLabel.setLayoutData(selectFolderLabel);
		seledctFolderLabel.setText(I18n.getString("dialog.selectLocalFolder.selectLocalFolder"));

		// Textfield "Folder"
		GridData folderTextGridData = new GridData(SWT.FILL, SWT.CENTER, true, false, 1, 1);
		folderTextGridData.verticalIndent = 0;
		folderTextGridData.minimumWidth = 200;

		localDir = new Text(this, SWT.BORDER);
		localDir.setLayoutData(folderTextGridData);

		// Button "Select ..."
		Button selectFolderButton = new Button(this, SWT.FLAT);
		selectFolderButton.setText(I18n.getString("dialog.selectLocalFolder.selectLocalFolderButton"));
		selectFolderButton.setLayoutData(new GridData(SWT.LEFT, SWT.BOTTOM, false, false, 1, 1));
		selectFolderButton.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				onSelectFolderClick();
			}
		});

		messageLabel = new Label(this, SWT.WRAP);
		messageLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false, 3, 1));

		WidgetDecorator.normal(localDir, seledctFolderLabel, messageLabel);

	}
	
	public void setValidationMethod(SelectFolderValidationMethod validationMethod) {
		this.validationMethod = validationMethod;
	}
	
	public void setDescriptionText(final String descriptionTextStr) {
		Display.getDefault().asyncExec(new Runnable() {			
			@Override
			public void run() {
				descriptionText.setText(descriptionTextStr);
				layout();
			}
		});
	}
	
	@Override
	public boolean isValid() {
		switch (validationMethod) {
		case APP_FOLDER:
			return isValidAppFolder();
			
		case NO_APP_FOLDER:
			return isValidNoAppFolder();
			
		default:
			throw new RuntimeException("Invalid validation method: " + validationMethod);				
		}
	}

	private boolean isValidNoAppFolder() {
		File selectedDir = new File(localDir.getText());
		File appDir = new File(selectedDir, Config.DIR_APPLICATION);

		if (appDir.exists()) {
			WidgetDecorator.markAs(false, localDir);
			return false;
		}
		else {
			if (!selectedDir.isDirectory()) {
				boolean allowCreate = askCreateFolder(getShell(), selectedDir);
				WidgetDecorator.markAs(allowCreate, localDir);
				
				if (allowCreate) {
					if (selectedDir.mkdirs()) {
						WidgetDecorator.markAs(true, localDir);
						return true;
					}
					else {
						WidgetDecorator.markAs(false, localDir);
						return false;
					}
				}
				else {
					WidgetDecorator.markAs(false, localDir);
					return false;
				}
			}
			else {
				WidgetDecorator.markAs(true, localDir);
				return true;
			}
		}		
	}

	private boolean isValidAppFolder() {
		File selectedDir = new File(localDir.getText());
		File appDir = new File(selectedDir, Config.DIR_APPLICATION);

		boolean isValid = appDir.exists();
			
		WidgetDecorator.markAs(isValid, localDir);
		return isValid;
	}

	private boolean askCreateFolder(Shell shell, File selectedDir) {
		MessageBox dialog = new MessageBox(shell, SWT.ICON_QUESTION | SWT.OK | SWT.CANCEL);
		
		dialog.setText("Create Folder");
		dialog.setMessage(String.format("Would you like to create the folder [%s]?", selectedDir.getAbsolutePath()));

		int returnCode = dialog.open();

		if (returnCode == SWT.OK) {
			return true;
		}
		
		return false;
	}
	
	private void onSelectFolderClick() {
		DirectoryDialog directoryDialog = new DirectoryDialog(getShell());
		String selectedFolder = directoryDialog.open();

		if (selectedFolder != null && selectedFolder.length() > 0) {
			localDir.setText(selectedFolder);
		}
	}

	public File getFolder() {
		return new File(localDir.getText());
	}
}
