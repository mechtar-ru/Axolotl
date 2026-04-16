import { contextBridge, ipcRenderer } from 'electron';

export interface ElectronAPI {
  showNotification: (options: { title: string; body: string }) => Promise<boolean>;
  getAppVersion: () => Promise<string>;
  getAppPath: () => Promise<string>;
  showSaveDialog: (options: Electron.SaveDialogOptions) => Promise<Electron.SaveDialogReturnValue>;
  showOpenDialog: (options: Electron.OpenDialogOptions) => Promise<Electron.OpenDialogReturnValue>;
  readFile: (filePath: string) => Promise<string>;
  writeFile: (options: { filePath: string; content: string }) => Promise<boolean>;
  openExternal: (url: string) => Promise<void>;
  windowMinimize: () => Promise<void>;
  windowMaximize: () => Promise<void>;
  windowClose: () => Promise<void>;
  windowIsMaximized: () => Promise<boolean>;
  onCreateNewWorkflow: (callback: () => void) => void;
  onOpenWorkflow: (callback: () => void) => void;
  onSaveWorkflow: (callback: () => void) => void;
  onExportPng: (callback: () => void) => void;
  onExportJson: (callback: () => void) => void;
  removeAllListeners: (channel: string) => void;
}

const electronAPI: ElectronAPI = {
  showNotification: (options) => ipcRenderer.invoke('show-notification', options),
  getAppVersion: () => ipcRenderer.invoke('get-app-version'),
  getAppPath: () => ipcRenderer.invoke('get-app-path'),
  showSaveDialog: (options) => ipcRenderer.invoke('show-save-dialog', options),
  showOpenDialog: (options) => ipcRenderer.invoke('show-open-dialog', options),
  readFile: (filePath) => ipcRenderer.invoke('read-file', filePath),
  writeFile: (options) => ipcRenderer.invoke('write-file', options),
  openExternal: (url) => ipcRenderer.invoke('open-external', url),
  windowMinimize: () => ipcRenderer.invoke('window-minimize'),
  windowMaximize: () => ipcRenderer.invoke('window-maximize'),
  windowClose: () => ipcRenderer.invoke('window-close'),
  windowIsMaximized: () => ipcRenderer.invoke('window-is-maximized'),
  onCreateNewWorkflow: (callback) => ipcRenderer.on('create-new-workflow', callback),
  onOpenWorkflow: (callback) => ipcRenderer.on('open-workflow', callback),
  onSaveWorkflow: (callback) => ipcRenderer.on('save-workflow', callback),
  onExportPng: (callback) => ipcRenderer.on('export-png', callback),
  onExportJson: (callback) => ipcRenderer.on('export-json', callback),
  removeAllListeners: (channel) => ipcRenderer.removeAllListeners(channel)
};

contextBridge.exposeInMainWorld('electronAPI', electronAPI);
