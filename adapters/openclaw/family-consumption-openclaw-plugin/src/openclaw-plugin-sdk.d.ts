declare module 'openclaw/plugin-sdk/plugin-entry' {
  export type ToolDefinition = {
    name: string;
    description: string;
    parameters: unknown;
    execute: (id: string, params: any, context: unknown) => Promise<unknown>;
  };

  export type PluginApi = {
    registerTool: (tool: ToolDefinition, options?: { optional?: boolean }) => void;
  };

  export type PluginEntry = {
    id: string;
    name: string;
    description: string;
    register: (api: PluginApi) => void;
  };

  export function definePluginEntry(entry: PluginEntry): PluginEntry;
}
