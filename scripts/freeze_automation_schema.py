#!/usr/bin/env python3
"""Freeze the current MCP tool schema and synchronize paired OpenAPI bodies."""
import importlib.util
import json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('abwr_schema',ROOT/'bridge/mcp_stdio.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
version='0.14.0'
(ROOT/'docs/mcp-schema.json').write_text(json.dumps({'serverInfo':{'name':'abwr-browser','version':version},'schemaVersion':4,'toolCount':len(module.TOOLS),'tools':module.TOOLS},ensure_ascii=False,indent=2)+'\n')
p=ROOT/'docs/openapi.json';openapi=json.loads(p.read_text())
bindings={'/v1/network/observe':'browser_network_observe','/v1/prompts/resolve':'browser_prompt_resolve','/v1/files/import':'browser_file_import','/v1/storage/export':'browser_storage_export','/v1/downloads/action':'browser_download_action','/v1/proxy':'browser_proxy_set'}
for path,name in bindings.items():openapi['paths'][path]['post']['requestBody']['content']['application/json']['schema']=next(t['inputSchema'] for t in module.TOOLS if t['name']==name)
p.write_text(json.dumps(openapi,ensure_ascii=False,indent=2)+'\n')
