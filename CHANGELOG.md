# Changelog
## v4.3.1
### Features
- Enhance Tavily Extract (fetch_page): batch-extract up to 5 URLs, advanced depth, 6000 char limit (thanks @Shixiaoshi0417)
- fetch_page tool only exposed when search_provider=tavily
- Add search_memory / search_public_memory tools: AI can search memories by content keyword, not just by tag (thanks @Shixiaoshi0417)
- Search final round physically prevents tool calls, ensuring answer output
- Protocol: add <refmsgid> tag explanation, close </s>, SEWarden clean quoted text
- Tag rename: <qid>→<refmsgid>, <mop>→<memop>, <hit>→<tagresult>

### Bug Fixes
- Fix ctx ordering: user+R1 now saved before tool loops
- Fix callAI HTTP connection leak (missing finally disconnect)
- Fix dumpctx: quote extraction moved before export
- Fix overwrite_memory SQL: subquery replaces unsupported ORDER BY LIMIT
- Fix overwrite_memory: preserve original subject_uin on overwrite
- Fix /ai config: show missing keys (search_rounds, pat_wake, sewarden)
- Fix /help version, getMemberName duplicate check, canUseAi double getRole
- Fix onDestroy: save all contexts before clearing
- Fix listen mode: saveCtxToDisk after recording

## v4.2.1
### Features
- fix some bugs

## v4.2
### Features
- Add support for Tavily web search provider
- Allow configurable maximum search rounds
