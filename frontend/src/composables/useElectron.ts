import { ref, onMounted, onUnmounted } from 'vue';

export function useElectron() {
  const isElectron = ref(false);
  const isMaximized = ref(false);

  const api = window.electronAPI;

  onMounted(async () => {
    isElectron.value = !!api;
    if (api) {
      isMaximized.value = await api.windowIsMaximized();
    }
  });

  const showNotification = async (title: string, body: string) => {
    if (api) {
      return api.showNotification({ title, body });
    }
    if ('Notification' in window && Notification.permission === 'granted') {
      new Notification(title, { body });
      return true;
    }
    return false;
  };

  const getAppVersion = async () => {
    if (api) {
      return api.getAppVersion();
    }
    return 'web';
  };

  const getAppPath = async () => {
    if (api) {
      return api.getAppPath();
    }
    return '';
  };

  const showSaveDialog = async (options: Electron.SaveDialogOptions) => {
    if (api) {
      return api.showSaveDialog(options);
    }
    throw new Error('Save dialog not available in browser');
  };

  const showOpenDialog = async (options: Electron.OpenDialogOptions) => {
    if (api) {
      return api.showOpenDialog(options);
    }
    throw new Error('Open dialog not available in browser');
  };

  const readFile = async (filePath: string) => {
    if (api) {
      return api.readFile(filePath);
    }
    throw new Error('File read not available in browser');
  };

  const writeFile = async (filePath: string, content: string) => {
    if (api) {
      return api.writeFile({ filePath, content });
    }
    throw new Error('File write not available in browser');
  };

  const openExternal = async (url: string) => {
    if (api) {
      return api.openExternal(url);
    }
    window.open(url, '_blank');
  };

  const minimizeWindow = () => api?.windowMinimize();
  const maximizeWindow = async () => {
    if (api) {
      await api.windowMaximize();
      isMaximized.value = await api.windowIsMaximized();
    }
  };
  const closeWindow = () => api?.windowClose();

  const onCreateNewWorkflow = (callback: () => void) => {
    api?.onCreateNewWorkflow(callback);
  };

  const onOpenWorkflow = (callback: () => void) => {
    api?.onOpenWorkflow(callback);
  };

  const onSaveWorkflow = (callback: () => void) => {
    api?.onSaveWorkflow(callback);
  };

  const onExportPng = (callback: () => void) => {
    api?.onExportPng(callback);
  };

  const onExportJson = (callback: () => void) => {
    api?.onExportJson(callback);
  };

  const cleanupListeners = () => {
    if (api) {
      api.removeAllListeners('create-new-workflow');
      api.removeAllListeners('open-workflow');
      api.removeAllListeners('save-workflow');
      api.removeAllListeners('export-png');
      api.removeAllListeners('export-json');
    }
  };

  onUnmounted(cleanupListeners);

  return {
    isElectron,
    isMaximized,
    showNotification,
    getAppVersion,
    getAppPath,
    showSaveDialog,
    showOpenDialog,
    readFile,
    writeFile,
    openExternal,
    minimizeWindow,
    maximizeWindow,
    closeWindow,
    onCreateNewWorkflow,
    onOpenWorkflow,
    onSaveWorkflow,
    onExportPng,
    onExportJson,
    cleanupListeners
  };
}
