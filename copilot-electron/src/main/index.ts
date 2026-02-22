import { app, BrowserWindow } from 'electron'
import path from 'path'
import { fileURLToPath } from 'url'
import { registerIpc } from './ipc'

const __filename = fileURLToPath(import.meta.url)
const __dirname = path.dirname(__filename)

const windows = new Map<number, BrowserWindow>()

function createWindow(): BrowserWindow {
  const win = new BrowserWindow({
    width: 1400,
    height: 900,
    minWidth: 800,
    minHeight: 600,
    webPreferences: {
      preload: path.join(__dirname, '../preload/index.cjs'),
      contextIsolation: true,
      nodeIntegration: false,
    },
    titleBarStyle: 'hiddenInset',
    title: 'Copilot Desktop',
  })

  windows.set(win.id, win)

  if (process.env.NODE_ENV === 'development') {
    win.loadURL('http://localhost:5173')
    win.webContents.openDevTools()
  } else {
    win.loadFile(path.join(__dirname, '../renderer/index.html'))
  }

  win.on('closed', () => {
    windows.delete(win.id)
  })

  return win
}

app.whenReady().then(() => {
  createWindow()
  registerIpc()

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow()
    }
  })
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit()
  }
})

/** Create a new window for multi-window support (Phase 12) */
export function createNewWindow(): BrowserWindow {
  return createWindow()
}

export function getMainWindow(): BrowserWindow | null {
  const wins = BrowserWindow.getAllWindows()
  return wins.length > 0 ? wins[0] : null
}

export function getAllWindows(): BrowserWindow[] {
  return Array.from(windows.values())
}
