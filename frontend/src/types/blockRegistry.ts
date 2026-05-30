export interface ConfigPanelSection {
  id: string
  component?: string
  props?: Record<string, unknown>
}

export interface BlockDefinition {
  type: string
  label: string
  category: 'receive' | 'execute' | 'analyze' | 'output'
  color: string
  icon: string
  defaultConfig: Record<string, unknown>
  configPanels: ConfigPanelSection[]
}
