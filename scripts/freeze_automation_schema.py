#!/usr/bin/env python3
"""Freeze the current MCP tool schema and synchronize paired OpenAPI bodies."""
import importlib.util
import json
import ast
import copy
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('abwr_schema',ROOT/'bridge/mcp_stdio.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
version='0.14.0'
(ROOT/'docs/mcp-schema.json').write_text(json.dumps({'serverInfo':{'name':'abwr-browser','version':version},'schemaVersion':4,'toolCount':len(module.TOOLS),'tools':module.TOOLS},ensure_ascii=False,indent=2)+'\n')
p=ROOT/'docs/openapi.json';openapi=json.loads(p.read_text())
bindings={'/v1/network/observe':'browser_network_observe','/v1/prompts/resolve':'browser_prompt_resolve','/v1/files/import':'browser_file_import','/v1/storage/export':'browser_storage_export','/v1/downloads/action':'browser_download_action','/v1/proxy':'browser_proxy_set'}
for path,name in bindings.items():openapi['paths'][path]['post']['requestBody']['content']['application/json']['schema']=next(t['inputSchema'] for t in module.TOOLS if t['name']==name)
source=ast.parse((ROOT/'bridge/mcp_stdio.py').read_text())
actions=next(ast.literal_eval(node.value) for node in ast.walk(source) if isinstance(node,ast.Assign) and any(isinstance(target,ast.Name) and target.id=='actions' for target in node.targets))
actions['browser_navigate']='navigate'
schemas=openapi.setdefault('components',{}).setdefault('schemas',{})
variants=[]
for name,action in sorted(actions.items()):
    schema=copy.deepcopy(next(t['inputSchema'] for t in module.TOOLS if t['name']==name))
    schema['properties']['action']={'type':'string','const':action}
    schema['required']=['action']+schema['required']
    key='Command_'+action
    schemas[key]=schema;variants.append({'$ref':'#/components/schemas/'+key})
openapi['paths']['/v1/command']['post']['requestBody']['content']['application/json']['schema']={'oneOf':variants,'description':'Action-specific bodies mirror the paired MCP inputs. Cross-field path/timing and current-document checks run before dispatch.'}
p.write_text(json.dumps(openapi,ensure_ascii=False,indent=2)+'\n')
