import { existsSync, realpathSync, statSync } from 'node:fs';
import path from 'node:path';
import { definePluginEntry } from 'openclaw/plugin-sdk/plugin-entry';

const DEFAULT_API_BASE_URL = 'http://localhost:8080';
const DEFAULT_IMPORT_ALLOWED_DIRS = ['examples', 'data/imports', 'imports'];
const TOOL_IMPORT_FILE = 'family_repurchase_import_file';
const TOOL_COMPARE_PRICE = 'family_repurchase_compare_price';
const TOOL_GENERATE_REPORT = 'family_repurchase_generate_report';

type PluginConfig = {
  apiBaseUrl?: string;
  projectRoot?: string;
  importAllowedDirs?: string[];
};

type ImportFileArgs = {
  filePath: string;
  owner?: string;
};

type ComparePriceArgs = {
  productName: string;
  price: number;
  quantity: number;
  unit: string;
};

type GenerateReportArgs = {
  month: string;
};

export default definePluginEntry({
  id: 'family-repurchase-agent',
  name: 'Family Repurchase Agent',
  description: 'Expose local household consumption analysis tools to OpenClaw.',
  register(api) {
    api.registerTool({
      name: TOOL_IMPORT_FILE,
      description: 'Import a local CSV or Excel order file through the Spring Boot REST API.',
      parameters: objectSchema({
        filePath: stringSchema('Local CSV or Excel file path.'),
        owner: optionalStringSchema('Optional purchase owner.')
      }, ['filePath']),
      async execute(_id, params: ImportFileArgs, ctx) {
        const config = readConfig(ctx);
        const safeFile = validateImportFilePath(params.filePath, config);
        const body: Record<string, unknown> = { filePath: safeFile.realPath };
        if (params.owner?.trim()) {
          body.owner = params.owner.trim();
        }
        return toolJson(await postJson(config, '/api/tools/import-file', body));
      }
    }, { optional: true });

    api.registerTool({
      name: TOOL_COMPARE_PRICE,
      description: 'Compare current unit price with local purchase history through the Spring Boot REST API.',
      parameters: objectSchema({
        productName: stringSchema('Product name.'),
        price: positiveNumberSchema('Current total price or paid amount.'),
        quantity: positiveNumberSchema('Current quantity.'),
        unit: stringSchema('Unit, for example kg, pack, or piece.')
      }, ['productName', 'price', 'quantity', 'unit']),
      async execute(_id, params: ComparePriceArgs, ctx) {
        const config = readConfig(ctx);
        requirePositiveNumber(params.price, 'price');
        requirePositiveNumber(params.quantity, 'quantity');
        return toolJson(await postJson(config, '/api/tools/compare-price', {
          productName: requireText(params.productName, 'productName'),
          price: params.price,
          quantity: params.quantity,
          unit: requireText(params.unit, 'unit')
        }));
      }
    });

    api.registerTool({
      name: TOOL_GENERATE_REPORT,
      description: 'Generate a monthly Markdown consumption report through the Spring Boot REST API.',
      parameters: objectSchema({
        month: {
          type: 'string',
          pattern: '^\\d{4}-\\d{2}$',
          description: 'Report month in yyyy-MM format.'
        }
      }, ['month']),
      async execute(_id, params: GenerateReportArgs, ctx) {
        const config = readConfig(ctx);
        return toolJson(await postJson(config, '/api/tools/generate-report', {
          month: requireText(params.month, 'month')
        }));
      }
    }, { optional: true });
  }
});

function readConfig(ctx: unknown): PluginConfig {
  if (typeof ctx === 'object' && ctx !== null && 'config' in ctx) {
    const value = (ctx as { config?: unknown }).config;
    return typeof value === 'object' && value !== null ? value as PluginConfig : {};
  }
  return {};
}

function toolJson(data: unknown) {
  return {
    content: [
      {
        type: 'text',
        text: JSON.stringify(data, null, 2)
      }
    ],
    structuredContent: data
  };
}

async function postJson(config: PluginConfig, apiPath: string, body: Record<string, unknown>) {
  const apiBaseUrl = resolveApiBaseUrl(config);
  let response: Response;

  try {
    response = await fetch(`${apiBaseUrl}${apiPath}`, {
      method: 'POST',
      headers: {
        'content-type': 'application/json'
      },
      body: JSON.stringify(body)
    });
  } catch (error) {
    throw new Error(`Failed to connect to Family Repurchase Agent backend at ${apiBaseUrl}: ${messageOf(error)}`);
  }

  const text = await response.text();
  const data = parseResponseBody(text);

  if (!response.ok) {
    throw new Error(errorText(data) || `REST API request failed: HTTP ${response.status}`);
  }

  return data;
}

function validateImportFilePath(filePath: string, config: PluginConfig) {
  const originalPath = requireText(filePath, 'filePath');
  const projectRoot = resolveProjectRoot(config);
  const normalizedPath = path.isAbsolute(originalPath)
    ? path.normalize(originalPath)
    : path.resolve(projectRoot, originalPath);

  if (!existsSync(normalizedPath)) {
    throw new Error(`filePath does not exist: ${filePath}`);
  }

  const realPath = realpathSync(normalizedPath);
  const allowedDirs = resolveImportAllowedDirs(config, projectRoot);
  const allowed = allowedDirs.some(allowedDir => isPathInside(realPath, allowedDir));
  if (!allowed) {
    throw new Error(`filePath is outside allowed import directories. Allowed directories: ${allowedDirs.join(', ')}`);
  }

  if (!statSync(realPath).isFile()) {
    throw new Error(`filePath must be a file: ${filePath}`);
  }

  const lower = realPath.toLowerCase();
  if (!lower.endsWith('.csv') && !lower.endsWith('.xlsx') && !lower.endsWith('.xls')) {
    throw new Error('filePath must be a CSV or Excel file');
  }

  return { originalPath, realPath };
}

function resolveApiBaseUrl(config: PluginConfig) {
  return trimTrailingSlash(process.env.FAMILY_AGENT_API_BASE_URL || config.apiBaseUrl || DEFAULT_API_BASE_URL);
}

function resolveProjectRoot(config: PluginConfig) {
  return path.resolve(config.projectRoot || process.env.FAMILY_AGENT_PROJECT_ROOT || '.');
}

function resolveImportAllowedDirs(config: PluginConfig, projectRoot: string) {
  const fromEnv = process.env.FAMILY_AGENT_IMPORT_ALLOWED_DIRS;
  const rawDirs = fromEnv
    ? fromEnv.split(path.delimiter)
    : (config.importAllowedDirs?.length ? config.importAllowedDirs : DEFAULT_IMPORT_ALLOWED_DIRS);
  return rawDirs
    .map(value => value.trim())
    .filter(Boolean)
    .map(value => path.isAbsolute(value) ? value : path.resolve(projectRoot, value))
    .map(value => existsSync(value) ? realpathSync(value) : path.normalize(value));
}

function isPathInside(file: string, directory: string) {
  const relative = path.relative(directory, file);
  return relative === '' || (!relative.startsWith('..') && !path.isAbsolute(relative));
}

function requireText(value: unknown, name: string) {
  if (typeof value === 'string' && value.trim()) {
    return value.trim();
  }
  throw new Error(`${name} must be a non-empty string`);
}

function requirePositiveNumber(value: unknown, name: string) {
  if (typeof value !== 'number' || !Number.isFinite(value) || value <= 0) {
    throw new Error(`${name} must be a positive number`);
  }
}

function parseResponseBody(text: string): Record<string, unknown> {
  if (!text) {
    return {};
  }
  try {
    return JSON.parse(text);
  } catch {
    return { raw: text };
  }
}

function errorText(data: Record<string, unknown>) {
  for (const key of ['message', 'error', 'raw']) {
    const value = data[key];
    if (typeof value === 'string' && value.trim()) {
      return value;
    }
  }
  return null;
}

function objectSchema(properties: Record<string, unknown>, required: string[]) {
  return {
    type: 'object',
    properties,
    required,
    additionalProperties: false
  };
}

function stringSchema(description: string) {
  return {
    type: 'string',
    description
  };
}

function optionalStringSchema(description: string) {
  return {
    type: 'string',
    description
  };
}

function positiveNumberSchema(description: string) {
  return {
    type: 'number',
    exclusiveMinimum: 0,
    description
  };
}

function trimTrailingSlash(value: string) {
  return value.replace(/\/+$/, '');
}

function messageOf(error: unknown) {
  return error instanceof Error ? error.message : String(error);
}
