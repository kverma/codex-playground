/** Standalone genuine MCP stdio server: npm run mcp:appstore */
import {serveStdio} from '@modelcontextprotocol/server/stdio';
import {createMockAppStoreServer} from './mock-app-store.ts';
await serveStdio(()=>createMockAppStoreServer());
