/*
 * Syncany, www.syncany.org
 * Copyright (C) 2011-2014 Philipp C. Heckel <philipp.heckel@gmail.com> 
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.syncany.gui.wizard;

import java.io.File;

import org.syncany.gui.util.I18n;
import org.syncany.gui.wizard.FolderSelectPanel.SelectFolderValidationMethod;
import org.syncany.gui.wizard.WizardDialog.Action;
import org.syncany.operations.daemon.ControlServer.ControlCommand;
import org.syncany.operations.daemon.messages.AddWatchManagementRequest;
import org.syncany.operations.daemon.messages.AddWatchManagementResponse;
import org.syncany.operations.daemon.messages.ControlManagementRequest;

import com.google.common.eventbus.Subscribe;

/**
 * @author Philipp C. Heckel <philipp.heckel@gmail.com>
 */
public class AddExistingPanelController extends ReloadDaemonPanelController {
	private StartPanel startPanel;
	private FolderSelectPanel selectFolderPanel;
	private ProgressPanel progressPanel;
	
	public AddExistingPanelController(WizardDialog wizardDialog, StartPanel startPanel, FolderSelectPanel selectFolderPanel, ProgressPanel progressPanel) {
		super(wizardDialog, progressPanel);
		
		this.startPanel = startPanel;
		this.selectFolderPanel = selectFolderPanel;
		this.progressPanel = progressPanel;
	}

	@Override
	public void handleFlow(Action clickAction) {
		if (wizardDialog.getCurrentPanel() == startPanel) {
			if (clickAction == Action.NEXT) {
				selectFolderPanel.setValidationMethod(SelectFolderValidationMethod.APP_FOLDER);
				selectFolderPanel.setDescriptionText(I18n.getString("dialog.selectLocalFolder.watchIntroduction"));

				wizardDialog.validateAndSetCurrentPanel(selectFolderPanel, Action.PREVIOUS, Action.NEXT);
			}
		}
		else if (wizardDialog.getCurrentPanel() == selectFolderPanel) {
			if (clickAction == Action.PREVIOUS) {
				wizardDialog.setCurrentPanel(startPanel, Action.NEXT);
			}
			else if (clickAction == Action.NEXT) {
				progressPanel.setTitleText("Adding Syncany folder");
				progressPanel.setDescriptionText("Syncany is adding your folder to the configuration and restarting the daemon. This shouldn't take long.");

				boolean panelValid = wizardDialog.validateAndSetCurrentPanel(progressPanel);

				if (panelValid) {
					sendAddFolderRequest();
				}
			}
		}
		else if (wizardDialog.getCurrentPanel() == progressPanel) {
			if (clickAction == Action.PREVIOUS) {
				wizardDialog.setCurrentPanel(selectFolderPanel, Action.PREVIOUS, Action.NEXT);
			}
			else if (clickAction == Action.NEXT) {
				wizardDialog.validateAndSetCurrentPanel(startPanel);
			}
		}
	}

	private void sendAddFolderRequest() {
		File newWatchFolder = selectFolderPanel.getFolder();
		AddWatchManagementRequest addWatchManagementRequest = new AddWatchManagementRequest(newWatchFolder);
		
		progressPanel.resetPanel(3);
		progressPanel.appendLog("Adding folder "+ newWatchFolder + " ... ");

		eventBus.post(addWatchManagementRequest);		
	}

	@Subscribe
	public void onAddWatchManagementResponse(AddWatchManagementResponse response) {
		if (response.getCode() == AddWatchManagementResponse.OKAY) {
			progressPanel.increase();
			progressPanel.appendLog("DONE.\nReloading daemon ... ");
			
			eventBus.post(new ControlManagementRequest(ControlCommand.RELOAD));
		}
		else {
			progressPanel.finish();
			progressPanel.setShowDetails(true);
			progressPanel.appendLog("ERROR.\n\nUnable to add folder (code: " + response.getCode() + ")\n" + response.getMessage());
			
			wizardDialog.setAllowedActions(Action.PREVIOUS);			
		}
	}	
}
