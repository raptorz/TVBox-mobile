# 影視TV 使用與開發指南

五頁靜態文件站：首頁、爬蟲介接、配置字典、本地 API 及 App 功能。Next.js 在建置時產生 HTML、CSS、JavaScript；正式網站不需要 Node.js、Worker、資料庫或 ChatGPT 登入。搜尋、篩選、語言切換與複製均在瀏覽器執行。

## 本機使用

需要 Node.js 22.18+（22.x）或 Node.js 24+（預覽使用 Node 內建的 TypeScript 型別移除功能）。

```sh
cd website
npm ci
npm run dev
```

驗證與預覽真正的靜態輸出：

```sh
npm run lint
npm run build
npm start
```

`npm run build` 會建置靜態網站並執行 TypeScript 型別檢查。`npm start` 只提供 `out/` 內的檔案，網址為 `http://localhost:3000/`；它不是正式部署所需的伺服器。請透過 HTTP 預覽，不要直接雙擊 HTML。

不需要完整建置時，可選擇執行 `npm run typecheck` 快速檢查型別；此指令執行 `next typegen` 與 `tsc --noEmit`，不產生靜態網站輸出。

## 靜態部署

本分支已移除 GitHub Pages 自動發布工作流，改為 Android 簽章建置。網站仍可按上面的步驟本機建置，並將 `out/` 部署到自己的靜態主機。

上游文件網站為 [影視TV 使用與開發指南](https://fongmi.github.io/TV/)。部署到其他網址時，請透過 `SITE_URL` 設定路徑。

### 指定部署網址

PowerShell 子目錄測試：

```powershell
$env:SITE_URL = 'https://fongmi.github.io/TV/'
npm run build
npm start
```

預覽網址此時為 `http://localhost:3000/TV/`。`SITE_URL` 是建置時設定，預覽應使用與建置相同的值；更換網域或路徑需重新建置。完成後以 `Remove-Item Env:SITE_URL` 清除設定。

## 維護位置

- `app/`：頁面、共用元件、文件欄位及搜尋索引。
- `app/globals.css`：樣式與響應式規則。
- `public/`：SVG Logo、品牌預覽圖及 `.nojekyll`。
- `app/local/page.tsx`、`app/local-api.ts`：由舊 `docs/LOCAL.md` 整合、核對原始碼後的本地 HTTP API 文件與範例。

參考：[Next.js 靜態輸出](https://nextjs.org/docs/app/guides/static-exports)、[GitHub Pages 自訂流程](https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages)。
