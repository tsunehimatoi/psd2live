<!-- lang: zh en ko ja -->
> [中文](../../zh/agent/MCP_AUTHORING.md) · [English](../../en/agent/MCP_AUTHORING.md) · [한국어](../../ko/agent/MCP_AUTHORING.md) · **日本語**

# MCP の使い方とインターフェース

[ドキュメント目次](../README.md) · [設計と受け入れ](AGENT_DESIGN.md) · [能力の実測](../STATUS.md)

公開定義は、アプリケーション層の [WorkspaceAuthoringOperations.kt](../../../src/main/kotlin/io/github/psd2live/application/WorkspaceAuthoringOperations.kt)、[WorkspaceDocumentBatch.kt](../../../src/main/kotlin/io/github/psd2live/application/WorkspaceDocumentBatch.kt)、[WorkspaceAuxiliaryOperations.kt](../../../src/main/kotlin/io/github/psd2live/application/WorkspaceAuxiliaryOperations.kt)、[WorkspaceOperations.kt](../../../src/main/kotlin/io/github/psd2live/application/WorkspaceOperations.kt) にあります。MCP のバインドは [AgentToolCatalog.kt](../../../src/main/kotlin/io/github/psd2live/agent/AgentToolCatalog.kt) を参照してください。ツールは `domain_operation` という名前を使います。古い分岐ツールは削除済みで、互換エイリアスはありません。`workspace_list_operations` で能力をページングして見つけ、`workspace_get_operation` で 1 項目の完全な schema を読みます。

## 接続

1. デスクトップアプリを起動し、ワークスペースを読み込むか作成します。
2. **設定 → MCP**（タイトルバー右側の歯車）を開き、ホストに合わせてコマンドまたは設定をコピーします。Claude Code のコマンド、Codex の TOML、汎用の `mcpServers` JSON（アドレスのフィールドはホストによって異なります）、または Stdio プロキシです。「インストール用プロンプトをコピー」は、Agent が自分で設定するための短いプロンプトを生成します。
3. Streamable HTTP と Bearer トークンを優先します。廃止された `/sse` エンドポイントへは切り替えません。
4. Stdio だけに対応するホストは、Python 3 で `tools/mcp_proxy.py` を実行します。プロキシは `PSD2LIVE_MCP_ENDPOINT` と `PSD2LIVE_MCP_TOKEN` を読み、未設定のときは Windows 上で、アプリが保存したポートとトークンを読みます。スクリプトとコマンドラインも、これを使って呼び出します。`python tools/mcp_proxy.py call <操作> [JSON | @文件] [--edit] [--out 目录]`（`--edit` は現在の `state` を自動で入れ、画像は PNG として保存します）、`python tools/mcp_proxy.py operations` です。Python では `from mcp_proxy import Client` のあと `call` / `edit` です。どちらのツールセットにも当てはまり、バックグラウンド操作は終わるまで待ってから結果を返します。
5. ツールを列挙したあと `workspace_inspect` を呼び、実際のオブジェクト ID、パラメータ、現在の `project_id` と `state` を読みます。

同じダイアログで、サービスを有効にするか、ポート（1024–65535、既定 23871）、アクセストークン（32–256 文字の英字、数字、`-`、`_`。再生成するか、自分で記入できます）、ツールセットを設定します。ユーザー設定に保存し、適用するとエンドポイントを再起動します。再起動は、プロセス内のタスクとリクエストの重複排除記録を残します。接続済みのホストは再接続が必要で、ポートまたはトークンを変えたときはホストの設定も更新します。

トークンは現在のワークスペースを編集できるので、このマシンのホスト設定に残します。ツールは画像生成モデルを提供しません。新しい画像は、既存のピクセル、ペイント、またはホストの画像ジェネレーターから来ます。

## ツールセット

ツールセットが決めるのは、`tools/list` がどのツールを公開するかだけです。すべての操作は、どちらのツールセットでも呼び出せ、実行と検証は同じです。

- **簡略（既定）**。ツールは 27 個で、123 項目の操作を直接覆います（入力 schema と説明はおよそ 8.5 万文字。完全なツールセットはおよそ 76 万文字で、さらに出力 schema がおよそ 426 万文字です）。
  - 単項目ツールは、`workspace_overview`、`workspace_inspect`、`workspace_list_operations`、`workspace_get_operation`、`workspace_apply_edits`、`workspace_preview_edits`、`rig_deform`、`keyform_apply`、および意図ツールの `author_axis`、`author_physics`、`author_compile` です（[意図ツール](#意図ツール) を参照）。
  - 族ツールは、`view`、`parameter`、`motion`、`expression`、`hit_area`、`pose`、`skeleton`、`path`、`physics`、`simulation`、`swing`、`modifier`、`source_paint`、`snapshot`、`history`、`project`、`job` です。`{"op":"set_key","request":{...},"wait_ms":20000}` を呼ぶと `<族>_<op>` を実行します（この例は `motion_set_key`）。結果の `operation` はその操作 ID で、`request` はその操作の完全な schema で検証します。メンバーは、レジストリの中でその接頭辞を持つ操作のすべてです。プライベートな下書き、試聴、リアルタイムプレビューのセッション（`skeleton_draft_*`、`physics_audition*`、`*_preview*`）、履歴の注釈、グローバルな物理プリセットライブラリは除きます。族ツールは、各メンバーのフィールドの平坦な和集合を公開します（`oneOf`、`const`、`$ref` は含みません。メンバーによって形が異なるフィールドは説明だけです）。各 op の必須フィールドは、ツールの説明に列挙します。
  - その他の操作は `workspace_call` で呼びます。`{"operation":"layer_classify","request":{...}}` です。操作 ID は、同時に `workspace_apply_edits` のメンバー名です。
  - `outputSchema` は公開しません（結果は引き続き `structuredContent` で返り、契約は `workspace_get_operation` を参照します）。原子的なバッチの `edits` メンバーは、操作名の列挙と `request` オブジェクトだけを公開し、メンバーのフィールドは実行時に単項目の schema で厳密に検証します。
  - `request_id` と `project_id` は省略できます。`project_id` は、現在読み込んでいるプロジェクトを取ります。`state` は引き続き必須で、別の読み込みに属する `state` は通常どおり `state_conflict` を報告します。`request_id` は操作とパラメータ（`state` を含む）から派生し、同じパラメータの再試行は元の結果を取り戻します。前回の同じ呼び出しがすでに失敗しているとき（タスクの `failed/cancelled` を含む）は、次の呼び出しを新しい試行として実行します。
  - バックグラウンド操作は `wait_ms`（0–30000、既定 20000）を受け付けます。タスクが制限時間内に終われば、終状態と結果を直接返します（`job_wait` と同じ構造です）。そうでなければ実行中のタスクを返し、引き続き `job_wait` を使います。`wait_ms: 0` は、タスクハンドルをすぐに返します。
- **完全**。操作ごとにツールが 1 つで、完全な入力 schema と出力 schema を公開し、コンテキストは派生せず、タスクも待ちません。ツールを必要に応じて読み込むホスト、または項目ごとの契約が必要なクライアントに向きます。

## 意図ツール

意図ツールは、レジストリの中の普通の公開操作です（どちらのツールセットでも呼び出せます）。定義は [WorkspaceIntentOperations.kt](../../../src/main/kotlin/io/github/psd2live/application/WorkspaceIntentOperations.kt) にあります。書き込みの種類は、リクエストの `state` に対応するキャプチャ済みモデルの上で、意図を既存のバッチメンバーへコンパイルし、そのあと `workspace_apply_edits` と同じ原子的なバッチ（`startDocumentBatch`）を通ります。再構築、ジオメトリ検査、CAS はすべて同じで、1 回の成功は履歴ノードを 1 つだけ追加し、ログの意味は新たに足しません。タスクの結果は `workspace_apply_edits` と同じです（`edit_count` は、コンパイルしたメンバー数です）。コンパイル中に見つかった問題は `invalid_argument` で直ちに返し、メンバーの実行が失敗したときは `invalid_edit` でメンバーの番号と操作を示します。キャプチャしたバージョンがリクエストの `state` と異なるときは、`state_conflict` を報告します。

| ツール | コンパイル先 | 説明 |
| --- | --- | --- |
| `workspace_overview` | 読み取り専用の問い合わせ | 1 回のキャプチャで、キャンバス、パラメータ、レイヤー、デフォーマの階層（親と子オブジェクト数を含む）、メッシュ数、物理グループ（入力と出力のパラメータ）、モーションクリップを返します。各一覧には `total` が付き、`limit`（1–500、既定 200）で切り取ります。パラメータには `kind` と `repeat`（循環パラメータ）が含まれます |
| `author_axis` | `parameter_create`（パラメータがないとき）→ `keyform_apply` の seed → `rig_deform` | `parameter.id` がないときは作成します（`name` の既定は ID です）。すでに存在するとき、与えた `min/max/default` は現在値と一致しなければなりません。各形状の対象は、先にパラメータの既定値で seed し、現在の形状を中立形にします（その軸が既定値にキーポイントを持ち、`key` を与えていないときは飛ばします）。そのあと `value` で `rig_deform` の `operations/selection` を実行します。対象に直接バインドされたほかの軸は、それぞれの既定値を取り、`key` で組み合わせ角の値を指定できます。`value` が既定値と等しいときは拒否します。`mirror: true` のときは、各形状を反対の値へミラーします（`2×既定値 − value`。範囲内でなければなりません）。片側を書き終えると両側が揃います。新しいパラメータ軸のキーポイントは、エンジンが min、既定、max に従って作ります |
| `author_physics` | `physics_put` → `physics_fit` | フィールドは `physics_put` と同じで、`fit_target` を足します（10–300、既定 100。`null` はフィットを飛ばします）。`id` と `fit_target` だけを与えるときは、既存のグループをフィットし直すだけです。どちらも変えないときは拒否します。出力パラメータには、すでに作者の形状が必要です |
| `author_compile` | 読み取り専用の問い合わせ | 意図が確定するメンバーを返します。下記を参照してください |

意図ツールそのものは試行実行しません。`author_compile` は、リクエストの `state` に従って同じ意図をコンパイルし、確定するメンバーを返します（`{"intent":"author_axis","request":{...}}`。`request` は `state` を持ち、`request_id/project_id` は持ちません）。プロジェクトは変えません。確認または修正したあと、`workspace_preview_edits` に渡してその中のジオメトリメンバーを試行実行するか、`workspace_apply_edits` に渡して確定でき、結果は意図を直接呼んだときと同じです。

## 公開ツール早見

| ツール | リクエストの構造 | 用途 |
| --- | --- | --- |
| `workspace_list_operations` | 任意の `domain`、`kind`、`query`、`offset`、`limit` | ツール、業務の種類、実行メタデータをページングして見つけます。`query` の各語は、操作 ID または説明の中にすべて現れなければなりません（大文字と小文字は区別しません）。一覧の項目が持つのは説明の最初の文だけで、完全な説明は `workspace_get_operation` を参照します |
| `workspace_get_operation` | `id` | 1 項目の schema、フィールドの説明、実行メタデータを読みます |
| `workspace_apply_edits` | `state`、`edits`（1–128 項）、任意の `summary` | バックグラウンドで、同じ候補ドキュメントを順に編集し、すべて成功したあと履歴ノードを 1 つ確定します。タスクハンドルを返します。メンバーが対応するかは `batchable` を参照します |
| `workspace_preview_edits` | `state`、`edits`（1–128 項）。リクエストのコンテキストを共用 | バックグラウンドで、ジオメトリの作者編集を試行実行し、候補の revision と診断を返します。ドキュメント、ポーズ、履歴、リソースは公開しません |
| `workspace_preview_regeneration` | `state`、`edits`（1–128 項。素材レイヤーの操作を除く）。リクエストのコンテキストを共用 | バックグラウンドで、Rig を再生成する編集（設定、分類、分割、`rig_update_generation` など）を試行実行し、候補の revision、書き込むチェックポイントとそのマージの問題、増減するオブジェクトを返します。何も公開しません。明示的なスタックのプロジェクトは、さらに `modifiers` を返し、候補の各項目の依存状態、実際の寄与、上書きの衝突を含むので、単項目またはグループ全体を足す前に、予定の内容を見られます。同じ `state` とリクエストで確定するとき、候補の改訂と、自動で割り当てたインスタンスの身元は一致したままです |
| `workspace_inspect` | `request` 内 `scope` / `target` | scope は `project/settings/preview/generation/objects/layers/parameters/physics/swings/paths/simulations/interiors/vertex_groups` です。レイヤーの要約には、有効なメッシュ設定と意味タグ `tags` が含まれ、生成後に配置したレイヤー、または分類が生成を待っているレイヤーには `pending:true` が付き、素材からのものには `material_id` が付きます。`generation` は、生成済みか、生成を待つレイヤー、待っている分類、ジオメトリを更新できるレイヤーを返します。プロジェクトを読み込んでいるとき、`project` は `quality.overrides` を持ちます。観察レポート（バージョン 4）で、検査は 3 つです。記録どおりに効いていない生成結果の上書き（衝突 / 孤立。いずれも warning。色や不透明度などのチャンネルの上書きは `evidence.channel` がチャンネルを示します）、そのあとのバージョン 2 の分割が置き換えたメッシュだけを参照しているために空操作として再生したログエントリ（`SUPERSEDED_ENTRY_SKIPPED`、info、`target` は `journal:<序号>`）、および設定が要求するのに生成できなかった前髪越しの複製（`SEE_THROUGH_DROPPED`、info、`target` は原本の `mesh:<ID>`、`evidence.reason` は `original_missing` / `masks_missing` / `id_taken`。目のレイヤーが前髪に窓を開けるときは、さらに `bangs_clipped`（前髪がすでに自分のマスクを持つ）/ `target_unsupported`（ランタイム対象に逆マスクがない））です。さらに `quality.regeneration` を持ちます。観察レポート（バージョン 2、検査は 2 つ）で、先に、ログの最後のチェックポイントの再生成マージがきれいに移行できなかった内容を列挙します（`REGENERATION_CONFLICT` と `REGENERATION_TOPOLOGY_KEPT` は warning、`REGENERATION_DROPPED` は warning / validity、掛け替え、ジェネレーターの引き継ぎに伴う掛け替え（`REGENERATION_FOLLOWED`）、退役オブジェクトを残すこと、親の変更、頂点の移行、ユーザートポロジが生成に追従すること（`REGENERATION_TOPOLOGY_FOLLOWED`）、このプログラムでマージし直したチェックポイント（`REGENERATION_REMERGED`）は info）。そのあと、より古いバージョンがマージし、このプログラムがマージすると構造が異なり、Rig に影響する再生成チェックポイントを列挙します（`REGENERATION_REPAIR_AVAILABLE`、warning、`target` は `journal:<序号>`、`evidence.detail` は構造が異なるオブジェクトを列挙します。`rig_update_generation` を実行すれば修復できます。各モデルの最初の検査はマージし直すので、数秒かかることがあります）。`can_proceed` は常に true です。さらに `quality.skeleton` を持ちます。観察レポート（バージョン 1）で、ボーンにバインドしているのにその四肢のボーンに動かされないメッシュ（`SKELETON_BINDING_INEFFECTIVE`）、呼吸の Warp の下に掛かっているのにスケルトンの胴の Warp を迂回するメッシュ（`SKELETON_TORSO_BYPASSED`）を列挙し、いずれも warning です。また、Rig の中にないメッシュをバインドしているもの（`SKELETON_BINDING_MISSING`、info / validity）も列挙します。`target` は `mesh:<ID>`、`evidence.bone` はそれをバインドしたボーンで、`can_proceed` は常に true です |
| `layer_classify` | `request` の中の `state`、`layer_id`、および分類フィールド | バックグラウンドで、既存のソースレイヤーの種類、パーツ、左右、パラメータの関連、切り替え ID を更新します。`part` は、レイヤーがそのパーツの中で持つ役割を設定します（`highlight`、`pupil`、`lower`、`lid`、`shadow`、`blush`、`side`、`ahoge`、`ponytail`、`braid`、`animal`、`ribbon`、`pendant`、`rigid`、`sleeve`、`skirt`、`upperlip`、`lowerlip`、`upperline`、`lowerline`。`none` は役割なし、`auto` は解析に推定させます）。読むのは Rig モジュールだけです。省略したフィールドは、キャプチャ時の元の値を保ちます。生成済みのレイヤーは、すぐには生成し直しません。結果に `pending:true` が付き、次の `rig_generate` で効きます。すぐに効果を見たいときは、同じ `workspace_apply_edits` のバッチの末尾に `rig_generate` を足します。未生成のレイヤーは、すぐに採用します |
| `interior_put` | `state`、`target`。任意の `enabled/settings/motion/parameters/guides`（ガイド線は最大 64 本） | バックグラウンドで、単一メッシュの内部造形を生成または更新します。target はメッシュ ID または mesh:ID です。`enabled` だけを与えるときは、レシピを残して効果を切り替え、再サンプリングしません。パラメータを省略したときは、体の X/Y を優先し、明示的な空の ID は新規作成です。ネイティブのコンパクトなキーフォームと物理グループを書きます。`workspace_apply_edits` に入れられ、UI と作者の連鎖を共用します |
| `interior_delete` | `state`、`target`（Mesh ID または mesh:ID） | 内部造形と、自分の物理を削除します。ほかの機能が参照しているパラメータは残し、取り消しできます。`workspace_inspect scope=interiors` は、設定、ガイド線、入力と出力の ID、失効理由を読み戻します。変形の配列は返しません |
| `layer_mesh_update` | `request` の中の `state`、`layer_id`、`changes` または `reset` | バックグラウンドで、レイヤーごとに適応メッシュのパラメータを上書きまたはリセットします。現在値は `workspace_inspect scope=layers` で読みます |
| `source_sample_color` | `layer_id`、`x`、`y` | ソースレイヤーの、キャンバス整数座標の RGBA を読みます。ドキュメントも履歴も変えません |
| `source_paint_brush / source_paint_pencil / source_paint_eraser / source_paint_bucket / source_paint_shape / source_paint_clear` | `request` | `brush/pencil/eraser/bucket/shape/clear` です。座標はキャンバス単位で、レイヤーのラスタ密度に従って筆が落ち、1 つのジェスチャが履歴ノード 1 つです |
| `preview_set / preview_reset` | `request` の中の `state` | `set/reset` です。現在のプレビューのパラメータ値とロック状態を変え、キーフォームは書きません。`set` は `expression`（表情 ID）を与えられ、確定したポーズ（先に `values` を適用）の上で、その表情を全ウェイトで表示します。モーションパネルの表情プレビューと同じです |
| `snapshot_create / snapshot_update / snapshot_delete / snapshot_apply` | `state`。更新、削除、適用は `id` が必要です。作成と更新は `name`、`values` を与えられます | パラメータスナップショットの保存、改名または上書き、削除、適用です。適用はロックを残し、削除済みのパラメータを無視し、現在の範囲へクランプします。タイムラインのキーフレームは書きません |
| `snapshot_get / snapshot_list` | 読み取りは `id` を与えます。一覧は `offset`、`limit` を与えられます | 同じキャプチャのプロジェクト状態、スナップショットの ID と番号を返します。一覧は values を省略し、詳細は values を返します |
| `history_annotation_put / history_annotation_delete / history_annotation_get` | `node_id`。書き込みにはさらに `title`、`note`、`hidden` が必要です | 履歴ノードの表示用注釈を管理します。補助データはプロジェクトへ保存し、履歴ノードと Rig の revision は変わりません |
| `timeline_bookmark_set / timeline_shy_set / timeline_freeze_set / timeline_marker_put / timeline_marker_delete` | `state`。ブックマークは `parameter`、`bookmarked` を与えます。残りは `clip_id`（エディタのモーション ID。生成モーションは `preset:<名称>`）と、`parameter`、`shy` / `frozen`（凍結は `value` が必要）、またはマーカーの `time`、`label`、`color`、`id` を与えます | アニメーションエディタのタイムライン補助です。パラメータのブックマーク、隠す軌道と凍結した軌道、タイムラインマーカーです。補助データはプロジェクトとともに保存し、履歴は追加せず、書き出しもしません。凍結は、エディタが再生するときその値を保つだけで、`motion_*` がキーを変えることは止めません。`id` のないマーカーは、次の `marker_<n>` を取ります |
| `timeline_aids_get` | `clip_id` を与えられます | ブックマークと、各モーションの隠蔽、凍結値、マーカーを読みます |
| `canvas_guides_get / canvas_guides_set` | `set` には `state`、`guides` が必要です | 編集キャンバスのルーラー補助線を読むか、全体を置き換えます。各線は `{axis: x / y, position, id?}` です（キャンバスピクセル。`x` は縦線）。`id` がないときは次の `guide_<n>` を取り、最大 256 本で、空配列は消去します。キャンバスのルーラーと補助 CAS を共用し、プロジェクトとともに保存し、履歴には入らず、書き出しもしません |
| `module_catalog` | なし | 読み取り専用で、プロジェクトを開く必要はありません。Rig の部位（目、眉、口と下半顔、頭と顔、髪、体、衣装と装身、表情、モーション）、各部位の基礎アルゴリズム（互いに排他なやり方。最初はモジュール導入前の `legacy`）とモジュール（重ねられる能力。それぞれスイッチ。`tier` は core / major / minor の重要度、`requires` は依存）、および調整できる数値（単位、範囲、既定値）を列挙します。プロジェクトを開いているときは、さらに `modern`（モジュール導入前に保存したプロジェクトは false で、これまでどおり生成し、`settings_update` の `changes.rig: {}` でアップグレードします）、各部位の現在の基礎アルゴリズム、各モジュールが素材に適用できるか、有効か、コスト（追加パラメータ、キーフォーム、ブレンドシェイプ、物理グループ。有効なときは実測、無効なときは見積もりで `estimated:true`）を出します。[Rig プリセットのアーキテクチャ](../spec/RIG_PRESETS.md) を参照してください |
| `modifier_convert` | `state` | バックグラウンドで、古いプロジェクトを明示的なモディファイアスタックへ変換しようとします。モデルと書き出し結果の照合を通過してから、履歴ノードを 1 つ確定します。差があれば `modifier_conversion_mismatch` を返し、元のプロジェクトを残します。エラーには、差のあるフィールド、オブジェクトの増減と変更の数、一部の ID が含まれます |
| `modifier_catalog` | なし | 登録した型、バージョン、依存、提供する能力、互いに排他な族です。`groups` は、旧アルゴリズムと新アルゴリズムの、豊かさ 3 段階ずつを列挙します。`face/fitted` と `face/legacy` は互いに排他です。古い `face.depth-turn` は、再生の互換のためだけに残します。基礎の強度の範囲は 0–4 で、ほかの値はカタログの schema を基準にします |
| `modifier_plan` | `type` または `group`、固定した `targets`。ネイティブのスイング、物理、シミュレーション、モーションは `config` を与えられ、モーションと物理は単独で `preset` も与えられます | 読み取り専用で、ソース画像のバインド、またはネイティブジェネレーターを計画します。スイングは Warp を位置指定でき、残りの計画対象はメッシュです。ネイティブ設定を省略したときは、既存のパラメータと元のアルゴリズムで既定値を作ります。使えるパラメータがないときは拒否します。完全な追加 `entries`（安定 ID を割り当て済み）と、前置項目 `plan` を返し、追加、再利用、チェックポイント化済みを区別します。6 種のグループ ID は `modifier_catalog.groups` を参照します。`fitted` は `modern-standard` の互換エイリアスです。返した `group` と `entries` を `workspace_preview_regeneration` へ渡し、同じ `state` で `modifier_add_group` を確定できます。計画そのものはドキュメントを変えません |
| `modifier_stack` | なし | キャプチャしたバージョンのスタック、固定した対象、設定、出所のグループ、実際の寄与、状態を読み、次の変更に使える `state` を返します。`owned_keyforms` は、キーフォームを生成するオブジェクトとパラメータ参照を列挙し、任意の `key` は、既存の軸の上に足したマスの完全なパラメータ座標を示します。`override_issues` は、上書きの衝突、または一致させられない上書きを報告します。シミュレーションのベイクは要約だけを返し、頂点配列は返しません |
| `modifier_add` / `modifier_add_group` | `state`。単項目の `type`、`targets`、`config`、またはグループの `group`、`entries` | すでに明示的なスタックがあるときだけ効きます。`id` でインスタンスの身元を先に固定でき、そのあとのバッチメンバーが参照します。省略したときはドキュメントが割り当てます。`version` を省略すると、その型の既定バージョンを選び、保存後はバージョンを固定します。明示的に選ぶためだけのバージョンは `version` で指定でき、`plugin_list` が返すパッケージ入口の型を `modifier_plan` に渡すこともできます。`index` で挿入位置を明示できます（0 から。省略は追加です）。既存項目は自動では並べ替えません。設定は型に従って厳密に検証します。グループは共通の出所だけを記録し、各項目は独立したままです。1 回の呼び出し、またはバッチ全体で、履歴ノードは 1 つだけ確定します |
| `modifier_update` / `modifier_enable` | `state`、`id`。`type` と完全な `config`、または `enabled` | インスタンスの設定を調整するか、有効と無効を切り替えます。インスタンスの身元、アルゴリズムのバージョン、キャプチャした対象は変えません。無効にしても、その上書き記録は残します |
| `modifier_move` / `modifier_remove` | `state`、`id`。移動には、0 から始まる `index` がさらに必要です | 移動は実際の評価順を変えます。依存に反するときは拒否します。取り除くと、休止した上書きを残してその身元を退役させ、新しいインスタンスはそれを継承しません |
| `modifier_apply` | `state`、`id` | その項目までの完全な接頭辞をチェックポイント化し、接頭辞のインスタンスを取り除き、そのあとの項目は残します。物理は普通の物理設定になり、モーションはファイル名を残した普通のクリップになります。再構築の結果が適用前と等しいときだけ確定します。以上の変更はいずれもプロセスタスクを返し、`job_wait` / `job_get` で終状態を読めます |
| `settings_update` | `request` 内 `state`、`changes` | バックグラウンドで、自動 Rig、メッシュ、テクスチャ、高解像度化、物理プリセット、モーション、書き出しの設定を変更します。`rigTuning` はフィールドごとに Rig 数値をマージします（頭部の転向、目、眉、口、鼻と耳、髪、および体の回転、上下、前後の傾き、大きさの変化、体の Z と呼吸、立体とパース。単位はモデルプリセットの各部位カードの「数値」と一致し、範囲を超えるフィールドと未知のフィールドは拒否します）。`rig` は Rig のレシピを変更します。`bases: {部位: {base, values}}` が基礎アルゴリズムを選び、`modules: {模块: {on, values}}` がモジュールを切り替え、数値を調整します。同じ基礎アルゴリズムまたはモジュールの `values` は、既存の数値とマージします。項目が `null` のときは既定へ戻します（モジュールは有効へ戻ります）。`rig: {}` はモジュール導入前のプロジェクトをアップグレードし、`rig: null` は従来の生成方式へ戻します。元のスイッチを引き継ぐモジュール（`eye.see-through`、`eye.jelly`、`brow.see-through`、`mouth.outline`）の `on` は、対応する設定へ書きます。`moves: {特征: {手柄: [dx, dy] 或 null}}` は、フィットした顔パーツのハンドルを動かします（特徴は `eye.L` / `eye.R` / `brow.L` / `brow.R` / `mouth`。ハンドルは順に、目の `inner` / `outer` / `upper` / `lower`、眉の `inner` / `outer` / `arch`、口の `left` / `right` / `upper` / `lower`。単位はキャンバスピクセルで、原画のフィット位置に対する相対です。`null` は、ハンドルまたは特徴全体をフィット位置へ戻します）。未知の部位、基礎アルゴリズム、モジュール、数値、特徴、ハンドル、および範囲を超える値は拒否します（部位とモジュールは `module_catalog` を参照）。`meshOnly` が変わり、`generateDeformers` を明示していないときは `!meshOnly` に連動します（CMO3 のインポートを除きます）。生成モーションの子項目が変わり、`exportMotions` を明示していないときは、子項目がすべて有効かどうかに連動します。明示したフィールドが優先します。出所を閉じるときは、それが駆動するオーサードのポーズを、ワークスペースごとにパラメータの既定値へ解放し（ロックしたパラメータは飛ばします）、設定と同じ確定にします。ドキュメントは元の設定を保存します。先に `workspace_inspect scope=settings` で読みます |
| `project_import_psd` | `path`。任意の `discard_unsaved`、`modifiers`、`preset` | 絶対パスの PSD から、バックグラウンドでプロジェクトを作ります。初期モディファイアは `recommended`（既定）、`empty`、`minimal`、`default`、`full`、または `custom` を選べます。`custom` は `preset` を与えなければなりません。`headStrength`、`bodyStrength`（0–4）、`featureDisplacement`、`mouthOutline`、`motionBasic`、`motionSkeleton`、`clothing`、`eyeJelly` の真偽値、および `frontHair`、`backHair`（`CLASSIC`、`SIMULATION`、`OFF`）を含められます。ほかのモードは `preset` を与えられません。クイック構成は、完全な等価検査のあと一度にインストールします。読み込み済みのときは切り替えられ、未保存の変更は既定で拒否します |
| `project_create_artwork` | `width`、`height`、`layers`。任意の `discard_unsaved` | ローカルの PNG、WebP、TIFF、BMP から、位置に従って新しいプロジェクトを作ります。レイヤーは下から上です。バックグラウンドのタスクで、切り替えるときは保存するか、明示的に放棄します |
| `project_create` | `width`、`height`。任意の `discard_unsaved` | バックグラウンドで、未生成の空のプロジェクトを新規作成します。先に素材をインポートして配置し、そのあと生成するためです。[素材と Layout](#素材と-layout) を参照してください |
| `project_import_layout` | `path`。任意の `discard_unsaved` | [Layout マニフェスト](../spec/LAYOUT_MANIFEST.md)（絶対パスの `.json`、またはルートにマニフェストを置いた `.zip`）から、バックグラウンドで未生成の Layout プロジェクトを新規作成します |
| `project_import_cmo3` | `path`、`mode`（`new/replace`）。任意の `discard_unsaved` | 絶対パスの CMO3 をバックグラウンドでインポートします。新規作成は、未保存の切り替えを既定で拒否します。置き換えはオブジェクト ID で更新し、現れなかったオブジェクトを残し、履歴の編集 1 回として確定します |
| `project_open` | `path`。任意の `discard_unsaved` | 絶対パスのプロジェクトアーカイブをバックグラウンドで開きます。未保存は既定で拒否するので、先に保存するか明示的に放棄します。空のワークスペースからも呼び出せます |
| `project_save` / `project_save_as` | 保存に追加のフィールドは不要です。名前を付けて保存は `path` が必要です | プロジェクトをバックグラウンドで保存します。最初の保存は名前を付けて保存で絶対パスを指定し、そのあとの保存は現在のパスを使います。変更がなければ履歴ノードは追加しません |
| `project_export_model` | `request_id`、`state`、`output_directory` | モデルのファイル一式をバックグラウンドで書き出します。タスクハンドルを返し、ディレクトリは絶対パスでなければなりません |
| `project_export_target` | `request_id`、`state`、`target`、`output_directory`。任意の `settings` | 確定済みのモデルを、1 つの書き出し先に従ってバックグラウンドで書き出します。「ファイル → 書き出し形式」と同じです（実験的な対象を除きます）。`settings` は、その対象自身の設定キーです（`psd2live targets` が列挙します）。結果にはファイルと損失が含まれます |
| `project_export_psd` | `request_id`、`state`、`path` | PSD をバックグラウンドで書き出します。1 倍、2 倍、4 倍と、生成レイヤーのオプションに対応します |
| `job_get` / `job_wait` | `id`。待ちには `timeout_ms`（0–30000）を与えられます | 進捗、終状態、結果を問い合わせます。待ちをキャンセルしても、タスクはキャンセルしません |
| `job_list` | 任意の `project_id`、`offset`、`limit` | アプリ内のタスクをページングして問い合わせます |
| `job_cancel` | `request_id`、`id` | キャンセルを要求します。確定が終わっているものは completed の状態を残します |
| `rig_deform` | `request` の中の `state`、`changes` | 明示したパラメータキーの上で、Mesh / Warp の連続形状を編集します。`selection.indices` はインデックスで点を選び（`mesh_get_topology` が列挙します）、`soft_radius`（`radius` と同じ正規化枠）と `falloff`（既定は `smooth`。ほかに `sphere/root/inverse_square/sharp/linear/constant/random`）を付けると、周囲の点が距離に従って追従します。エディタのソフト選択です。一時的な変形格子が選択点を押す効果は、`landmarks` で表します |
| `keyform_apply` | `request` の中の `state`、`changes` | `op: seed/copy/set/delete/mirror/reset/interpolate/paste/multi_key` で、キーフォームの集合、スカラー / 色のチャンネル、回転の形状を編集します。回転の `set` が一部のフィールドだけを与えるとき（たとえば `angle` だけ）、残りのフィールドは、その回転がこのキーポイントで持つ現在値を取ります |
| `rig_create_warp` | `name`、`targets`。任意の `id`、`rows/columns`、`fit_local` | バックグラウンドで、親 Warp を共用する Mesh の独立した Warp を作り、原子的なバッチにも対応します。終状態は `target` を返します |
| `object_edit_appearance` | `request` の中の `state`、`edits` | 名称、表示と非表示、構造などの順序付き編集です。メッシュの `bind` の `space=local` は局所座標とキーフォームを残し（継承した動きと位置は新しい親に従って変わります）、`space=canvas` は静止メッシュ、キーフォーム、ブレンドシェイプを新しい親で換算し、メッシュを既定ポーズでキャンバスの元の位置に残します（階層ツリーのドラッグと、インスペクターでの親の変更は、これを使います）。デフォーマの `move` は `space=local` だけを受け付けます |
| `rig_edit_structure` | `request` の中の `state`、`edits` | 静的なオブジェクト属性（`blend_mode` は `workspace_inspect` が読み戻す同じ一組のブレンドモード名。たとえば `Normal`、`Multiply`、`Screen`）、デフォーマの削除と Part の帰属、パラメータフォルダ、XY の関連です |
| `object_paste / mesh_duplicate / object_rename / object_convert_id` | `request` の中の `state` と、それぞれのフィールド | オブジェクトのコピーとペースト、メッシュトポロジをほかのメッシュへコピー、一括改名、ID の変換です。いずれもバッチにでき、[オブジェクトの複製、検索と置換、ID の変換](#オブジェクトの複製検索と置換id-の変換) を参照してください |
| `canvas_warp / canvas_rotation / canvas_glue / canvas_topology` | `request` | `warp/rotation/glue/topology` です。`glue` は、異なるアートプリミティブ `mesh_a` と `mesh_b` を同時に与えなければなりません。`rotation` の `origin` はキャンバスピクセル（y は下向き）で、確定するときに親デフォーマの局所空間へ換算します。`preservePose` の既定は true で、メッシュは静止ポーズのまま元の位置を保ちます。`topology` は、下記の[メッシュトポロジ](#メッシュトポロジ)を参照してください |
| `view_render_model / view_render_layer / view_render_context / view_render_poses / view_render_pad / view_check_coverage / view_compare_history / view_sample_motion` | `request` | `model/layer/context/poses/pad/coverage/compare/motion` です。モーションのサンプリングは読み取り専用のタスクハンドルを返し、終状態にはサンプリングのコラージュとパラメータ範囲が含まれます |
| `parameter_create / parameter_update / parameter_delete` | `request` | `create/update/delete` です。削除するときは、最後の既定値に従って、最も近い、すでに書いたキーポイントのスライスを選び、軸を折りたたみます。範囲を狭めるとき、モーションの中のそのパラメータのキーポイントとハンドルは新しい範囲へ挟み込みます。パラメータを削除するときは、モーションの中のそのパラメータのカーブも同時に取り除きます |
| `layer_import_images` | `request` | PNG、ロスレス WebP、TIFF、または BMP のファイルから、複数レイヤーのソース画像を一度にインポートします。バックグラウンドが終わると、レイヤーとメッシュのハンドルを返します |
| `layer_get_texture / atlas_get / atlas_render_page` | `layer_id`。`atlas_get` は任意の `page`。レンダリングは `page` が必要で、任意の `max_size` | 同じキャプチャから、レイヤーのキャンバス矩形、ラスタのピクセル、ネイティブ密度、テクスチャの覆いとタイル、またはテクスチャアトラスの予算、fit、案内、ページの占有、すべてのタイルを読みます。ページのレンダリングは読み取り専用のバックグラウンドタスクで、終状態は PNG を返します |
| `layer_set_canvas_rect / layer_replace_image / layer_set_pixel_density / atlas_set_tile / atlas_set_budget / atlas_pack` | `request` の中の `state` と、それぞれのフィールド | バックグラウンドで、レイヤーのキャンバス矩形を変え、任意の解像度の画像を置き換え、テクスチャ密度とロックを設定し、タイルを動かし、テクスチャアトラスの予算と自動配置を変え、または一度きりの配置をします。いずれも原子的なバッチに入れられます。[テクスチャとテクスチャアトラス](#テクスチャとテクスチャアトラス) を参照してください |
| `source_get_components / source_split_components / source_split_polygon / source_split_depth / rig_update_generation / asset_prepare_reference / asset_import_png / asset_register / asset_preview_composite / layer_add_from_asset / layer_set_placement / layer_finalize_placement / asset_inspect / asset_reprocess / layer_delete / layer_restore` | `request` | ソース画像の分割、分割記録のアップグレード、および素材の参考、インポート、位置合わせ、プレビュー、追加、配置、確定、検査、再処理、ソフト削除、復元です |
| `swing_put / swing_delete` | `request` | `put/delete` です。Warp または Mesh（自動で Warp を 1 枚包みます）の上に、左右 / 上下のスイングと振り子を生成します。`motions` は左右と上下を組み合わせ、`parallel` は複数の髪束を平行に揺らします。`tilt` / `offset_along` / `offset_across` は、スイングの矩形を回転し、平行移動します。`delete` は `bake` で普通のキーにできます。[スイング生成](../guide/SWING.md) を参照してください |
| `physics_put / physics_delete / physics_simulate / physics_fit / physics_config / physics_import` | `request` | `put/delete/simulate/fit/config/import` です。ID で任意の物理グループ（生成したプリセット、ボーン、スイングのグループを含みます）を新規作成または部分的に変更し、カスタムグループを削除するか生成値へ戻し、バックグラウンドで読み取り専用のステップ応答をサンプリングします。タスク結果でピークと安定時間を返し、標準の揺れ、または `observed_peaks` の実測ピークに従って出力倍率を調整し、計算順と計算 FPS を設定し、physics3.json をインポートします。[物理演算](../guide/PHYSICS.md) を参照してください |
| `simulation_put / simulation_delete / simulation_simulate / simulation_compare / simulation_bake / simulation_clear_bake` | `request` の中の `state`、`id` | `put/delete/bake/clear_bake` はプロセスタスクのハンドルを返します（終状態には、元の編集結果とベイクの診断が含まれます）。`simulate` と `compare` も読み取り専用のタスクハンドルを返し、プロジェクトは変えません。`compare` は `motions` の中のモーションで、ベイク結果と参照シミュレーションを比べ、視覚検査を返します。メッシュ上の 2D の布 / 髪のシミュレーションは、エディタの中だけで動きます。`bake` は、それを −30…30 の `ParamSim<id>_<k>` パラメータ（`keys` 個のキーポイント。`blend_shapes` が書き方を選び、既定は自動）と、フィットした振り子 `PhysicsSim_<id>` へベイクします。うなずき、体の上下、前後の傾きは、別に、平行移動を入力とする振り子 `PhysicsSim_<id>_y` が駆動する `ParamSim<id>_Y` へベイクします。物体を動かさない入力は参加しません。各入力の読み取り結果、検査モーション上の視覚検査（振り幅、遅れ、戻り、揺れなど）、および R²、誤差、パラメータの使用量、極値に達したフレームの割合、ジャーク比を返します。`force_inputs` は入力を強制的に残し、`training_clips` はモデル自身のモーションを訓練へ参加させます。`exaggeration` はモードのスイングを拡大し、ベイクし直す必要はありません。`auto_bake` が有効なとき（既定）は、`put` が同じ 1 ステップの中でベイクし直します。新規作成で `inputs` を与えないときは既定の入力を書き、空配列は入力なしです。`output_names` は、ベイクで出したパラメータと振り子の名前を変えます。`input_ranges` は入力ごとに訓練範囲を設定し、`vertical` は上下パラメータのスイッチです（`null` は自動）。`outputs` は、生成したモードパラメータの ID に従って、書き出す ID、範囲（±1…±100）、ゲイン（0…3）を変え、ベイクし直す必要はありません。`obstacles`（グループ全体の置き換え）は、粒子が外側で遮られる障害物です。別のメッシュの変形に従う円（頂点 `a`）、またはカプセル（`a` から `b`）で、`radius` から `radius_b` へグラデーションし、`friction` は 0…1 です。シミュレーションとベイクに参加し、p2lrt / ウェブの対象へ書き出します。物理パネルがシミュレーションの振り子へ持つ上書きは、ベイクし直したあと三者マージします。[シミュレーションとベイク](../guide/SIMULATION.md) を参照してください |
| `model_apply_preset` | `request` の中の `preset`、`state`、`layers`、`sway` | `front_hair/back_hair/clothing/auto_weights/classic_front_hair/classic_back_hair/remove_clothing` です。バックグラウンドでウェイトグループとプリセットの物理体を生成してベイクし、タスクハンドルを返します。成功すると履歴ノードは 1 つだけ増えます。髪のプリセットは、その種類の髪の古いスイングのパラメータ、デフォーマ、振り子を取り除きます。`classic_*` は復元します（`sway:false` なら、従来のスイングを閉じます）。`remove_clothing` は、衣装プリセットの物理体をすべて削除します。`clothing` は、上衣、下衣、衿飾り、袖、脚に着るもののうち、ゆったりした部分だけをシミュレーションします。各メッシュについて、部位（下衣にはさらにスカート / ズボンの判定と、腰の線、股、裾）、ゆとりの割合、垂れ始め、シミュレーションするかどうかを返します。[シミュレーションとベイク](../guide/SIMULATION.md#モデルプリセット) を参照してください |
| `vertex_group_update` | `request` の中の `state`、`target`、`name` | 規則 `fill/outline/gradient/glue/region` で生成するか、`delete` で、このソフトウェアだけが使う頂点ウェイトグループ（固定点、剛性など）を消します。書き出しはしません |
| `skeleton_get / skeleton_propose / skeleton_auto / skeleton_put / skeleton_enable / skeleton_bone / skeleton_move / skeleton_bind / skeleton_remove / skeleton_delete / skeleton_pose` | `request` | `get/propose/auto/put/enable/bone/move/bind/remove/delete/pose` です。スケルトンを読むか推定し、完全なスケルトンを確定し、ボーンとバインドを編集します。`delete` はスケルトン全体を削除します（そのあと、再び `auto` / `put` できます）。`pose` は FK/IK のパラメータ値を求め、履歴は書きません |
| `motion_list / motion_get / motion_sample / motion_put / motion_delete / motion_seed_builtin / motion_set_key / motion_delete_key / motion_remove_curve` | `request` | `list/get/sample/put/delete/seed_builtin/set_key/delete_key/remove_curve` です。補間したポーズを読み、モーションクリップ、パラメータ軌道、タイムラインのキーフレームを永続化して編集します |
| `path_get / path_list / path_preview / path_put / path_delete / path_deform` | `request` | `get/list/preview/put/delete/deform` |
| `history_checkpoint / history_list / history_checkout` | `request` | チェックポイントを作り、履歴を読むか、ノードを切り替えます |

パーツの設定（`eye`、`brow`、`mouth`、`face`、`body`、`hair`）は、厳密な検証を保ちます。子機能の設定は `subConfigs` に入り、キーは原子的な機能の型です。たとえば `mouth.outline` です。値は、対応するアルゴリズムが宣言する数値、物理、依存、色のフィールド、および固定の `targets` を受け付けます。折りたたみの記録は、子インスタンスの `id`、`version`、`outputAliases` も保存し、更新するときは編集していないフィールドを残すべきです。子バージョンが対応するのは、公開 facade のバージョン `1` だけで、評価は対応する実装バージョンへ写像します。未知のバージョンは実行を拒否します。縁取りのスイッチは `outline` です。眉と目の結合の規範キーは `lidCoupling` です。歴史的な `lid` は、規範キーがないときだけ読み、明示した規範キーの `false` が優先します。画面がそのスイッチを変えたあとは規範キーだけを書き、読み取りと履歴の切り替えは古い設定を書き換えません。

新しいプリセットが折りたたむのは、順序、身元、対象、依存を残せる、現在のパッケージ候補だけです。子機能の重複、無効なメンバー、途中にほかの手順があること、外部の子インスタンス参照、明示的なバージョン選択、またはパッケージの上書きがあると、関係するグループは展開したままになります。保存済みの厳密なパッケージロックは、自動では変換しません。

モーションモディファイアの `config` は、任意の `parameter_roles` に対応し、ランタイムの役割名をパラメータ ID の配列へ写像します。まばたきの追加計画は `EyeBlink` を記入します。評価はパラメータ参照を検査し、同じ役割の両立しない定義を拒否します。問い合わせは、関係の寄与の中で変化を報告します。接頭辞を適用したあと、役割は普通のモデルメタデータとして保存します。

チェックポイント化したあとのモーション問い合わせは、`captured_ranges: {参数ID: {min, max, repeat}}` を返せます。チェックポイント化したときのカーブの制御点を残すためです。パラメータの改名、削除、範囲の編集に伴って更新し、保存と回復に入ります。

物理の計画は、`preset: "PhysicsHairFront"`、`"PhysicsHairBack"`、または `"PhysicsEyeJelly"` で古い規則をキャプチャできます。モーションの計画は、`natural:Idle`、`natural:Blink`、`natural:Nod`、`natural:Shake`、`natural:Talk` で自然なモーションをキャプチャできます。物理プリセットと、明示的に選んだ自然なモーションは、欠けている基礎バインドを計画へ入れます。どちらも編集できる設定を返します。パラメータを、現在評価済みのモディファイアが作っているとき、消費者はその提供者を固定できます。問い合わせの `parameter_providers` は、パラメータ ID から提供者インスタンス ID への対応を返します。

表が列挙するのは業務フィールドです。すべての変更はさらに `request_id` を持たなければならず、ワークスペースの変更は `project_id` と `state` を持たなければなりません（簡略ツールセットは前の 2 つを省略できます。[ツールセット](#ツールセット) を参照）。読み取り専用のバックグラウンドサンプリング `physics_simulate/simulation_simulate/simulation_compare/view_sample_motion` も、この 3 つのフィールドを要求します。重複排除と、サンプリングするバージョンの固定のためです。各操作のフィールドは異なるので、呼ぶ前に、現在のサービスが提供する JSON Schema を読みます。すべての公開ツールは、一律に `{"request": {...}}` で包みます。公開と検証は、同じ `oneOf`、`const`、フィールド制約、説明を残し、外側と業務オブジェクトはどちらも未知のフィールドを拒否します。結果は一律に `{"ok":true,"operation":"...","data":{...}}` です。エラーは `ok:false` と `error.code/message` を含み、フィールド検証のエラーはさらに `field` を持ちます。PNG は MCP の画像コンテンツとして返します。

公開操作 195 項目のすべて（そのうち 70 項目がバックグラウンド、91 項目がバッチ可能）は、完全な `outputSchema` を宣言しなければならず、完全なツールセットがそれを公開し、能力詳細の `output_schema` はそれと一致します。成功の data と失敗の error は厳密に排他です。レジストリは、実行と重複排除の境界で業務結果を検証し、MCP は完全な返却の包みを検証し、結果の契約を欠くものは登録できません。バックグラウンド操作は、さらに終状態の `job_result_schema` を持たなければならず、バックグラウンドでない操作はそのフィールドを許しません。能力詳細は、ローカルの `$defs/$ref` で入れ子の schema を記述し、自分の schema を問い合わせても検証できます。実際の HTTP は、すべてのルート制約を残します。`output_contract` は、サービスの実装の結果が宣言と合わないことを表し、変更がロールバックされた証拠にはできません。

画像の追加は `layer_import_images` を使います。必須は `state` と、1–128 個の絶対パスからなる `paths` です。任意の `parent_deformer_id` は、既存の親デフォーマを指します。省略したときはモデルのルートにバインドします。任意の `anchor_layer_id` は、バッチ全体をそのレイヤー（またはメッシュ）のすぐ前に描きます。`in_front=false` はすぐ後ろです。バッチ内の最初の 1 枚が最も前で、描画順は明示的な上書きとして書きます。任意の `at` `[x, y]` は、各画像をそのキャンバス座標の中央に置きます（キャンバスの境界に接します）。`as_reference=true` は、レイヤースタックの中の参考レイヤーとしてインポートします（メッシュはなく、書き出しません）。レイヤー一覧へドラッグして「参考にする」を選ぶのと同じで、履歴ノードは 1 つのままです。透明な縁を切り取ったあと中央に置きます。ラスタは元の解像度を保ち、キャンバスを超えるときはキャンバス矩形だけをキャンバス内へ等比で縮小します（レイヤー密度は 1 ピクセル / キャンバス単位より大きくなります）。メッシュはキャンバス解像度で生成します。1 ファイルは最大 64 MiB、1600 万ピクセル、バッチ全体は最大 3200 万ピクセルです。1 回の成功は履歴ノードを 1 つだけ追加し、いずれかのファイルが失敗するとバッチ全体を公開しません。ファイルを読むので、原子的なドキュメントバッチのメンバーにはできません。新しいソース画像とメッシュのハンドルは、タスク終状態の `affectedLayerIds/affectedObjectIds` から得ます。元画像のピクセルはプロジェクトへ書き、そのあと開き直しても入力ファイルに依存しません。各画像はオーサード Rig の中に自分のメッシュを得ます（`canvas_mesh_create`。与えた親の下に掛かります）。ジェネレーターは、それを生成し直すことはありません。取り消しは、インポートの取り消しです。

レイヤー全体の変換は `layer_transform` を使います（どのレイヤーでもかまいません。インポートしたものでも PSD のものでも、ルートでも任意のデフォーマの下でも、バインドの有無も問いません）。必須は `state/layer_id` で、さらに `matrix [a,b,c,d,e,f]`（`x' = a·x + c·y + e`、`y' = b·x + d·y + f`。キャンバス単位、y は下）を与えるか、`translate [x,y]`、`scale`（数、または `[sx,sy]`）、`rotate`（度。画面上では時計回り）、`pivot [x,y]` を与えます。レイヤーがすでに持つ変換と合成します。ピクセル、テクスチャのタイル、テクスチャ座標は変わらず、再生成は引き起こしません。レイヤーの各メッシュは、静止ポーズでその変換に従って移動し（キーフォームはオフセットを保ちます）、レイヤーは自分のピクセルがどこに表示されるかを記録し、ペイントもそれに揃います（回転したレイヤーも同じです）。取り消しできる履歴の確定 1 回で、原子的なドキュメントバッチに入れられます。

メッシュの再構築は `layer_mesh_rebuild` を使います（必須は `state/layer_id`）。レイヤーの現在のピクセルとメッシュ設定に従って、そのメッシュを生成し直します。キーフォーム、パス、ウェイト、Glue は新しいトポロジへ移行し、全体を動かしたレイヤーは位置を保ちます。メッシュを生成し直すのは、次のときだけです。インポート、このコマンド、ペイントと画像の差し替えの `rebuild_mesh` オプション、メッシュ設定の変更、再生成です。メッシュのないレイヤーは、初めてペイントして見えるピクセルが出たときに、メッシュを作ります。メッシュの外へ描いたピクセルは表示されず、画面のメッシュパネルが割合を示し、「現在のピクセルで再構築」を出します。

テクスチャの構造に沿って点を敷き直すには `layer_mesh_structure` を使います（必須は `state/target`。target はメッシュ ID または `mesh:<ID>` です。任意の `settings` は、`sensitivity` が 0–1、`min_length` がソースピクセル、`near_density` が線上の点間隔を、残す縁の行の点間隔に対する倍数とするもの（既定 1）、`fill` が線のあいだに点を足すか（既定は否。縁の行と線上にだけ頂点があります）、`far_density` が塗り足すときの、元の内側の辺の長さに対する辺の長さ、`bands` が描いたしわの線の両側に行と横桟を足すか（既定は否）、`kinds` が `FOLD`/`SHADE`/`PATTERN` の部分集合、`max_lines` ≤64、`max_vertices` ≤16000 です）。外側の輪郭の行と縁の帯は動かさず、内側はテクスチャの中のしわ、明暗の境、模様の線に沿って再構築します。キーフォーム、パス、ウェイト、Glue はこれまでどおり移行し、`canvas_mesh_rebuild` を 1 本書きます。対象に内部造形があるときは、検出した TOPOLOGY のガイド線を置き換えます（手描き、およびすでに FOLD と標したものは残します）。同じ履歴ノードの中でベイクし直します（`interior_rebaked`）。メッシュにできないとき、または `max_vertices` を超えるときはエラーを返し、何も変更しません。`layer_mesh_structure_preview`（読み取り専用。パラメータは上と同じ。`state` はありません）は、認識した線（種類、採用したか `followed`、帯を足したか `banded`、ソースピクセルとキャンバス座標）と、新しいメッシュのキャンバス上の点と三角形を返します。詳細は、テクスチャ構造に沿った点の再配置を参照してください。

シミュレーションのモディファイアの設定は、`bake` を省略できます。候補の準備は、バックグラウンドで、そのインスタンスより前の実際のスタックをベイクし、結果を入力の身元とともに確定します。`auto_bake: false` のときは、有効な結果を与えなければなりません。期限切れの入力は、その変更全体を拒否します。プレビューもベイクは公開しません。キャンセルと CAS の失敗は、候補の接頭辞を残しません。

`object_edit_appearance` が生成オブジェクトに対して行う改名、表示と非表示、静的な見た目、階層の編集は、そのオブジェクトを作ったモディファイアに属します。`parameter_update` が生成パラメータの名称、範囲、既定値に対して行う変更は、そのパラメータを提供するインスタンスに属します。スタックの問い合わせで、再生できない上書きを見られます。

`rig.scaffold` は、任意の `featureDisplacement: true` を持ち、古い顔の特徴変位の変形階層を残すために使います。既定はオフです。任意の `corrections` は、古いレシピの `moves` と `eyeParts` の形式を使い、フィットしたハンドルと、目の構造の訂正を保存します。

`mouth.outline` は、適用できる口のメッシュに、上下の唇と独立したテクスチャを生成します。`values.thickness` の範囲は 0.5–8 ピクセル、既定は 1.5 です。任意の `color` は 0–16777215 の RGB 整数を受け付け、省略したときは元の素材から色を取ります。適用するとき、メッシュとテクスチャは一緒にチェックポイント化します。

`expression.toggles` は、対象の中のスイッチと差分のレイヤーに、パラメータと不透明度のキーフォームを生成します。差分は、古いアルゴリズムの半段の重なりを使い、パラメータ定義が両立するときは再利用します。スイッチと差分のパラメータは、生成するチェーンの物理入力になります。

目の透視（`eye.see-through`）と眉の透視（`brow.see-through`）は、対象メッシュと、元の前髪のアルゴリズムを使います。`values.opacity` は 0–100、既定は 50 です。問い合わせは、複製メッシュとマスクの関係を報告します。目の開窓が既存のマスクと衝突するときは、追加を拒否します。

まばたきの余震（`eye.aftershock`）と眉の戻り（`brow.spring`）は、元のアルゴリズムの物理グループを伴います。設定には `physics: {"原生组 ID": 完整物理配置}` を与えられます。許されるのは、モジュールカタログが宣言するグループだけです。両立する標準出力の物理グループは再利用し、`reused` と報告します。両立しない出力は、トランザクション全体を拒否します。物理グループは、接頭辞の適用に伴って普通の設定になり、変更したあとの値を保ちます。

物理設定のキーは、`eye.aftershock` の `PhysicsEyeLBounce`／`PhysicsEyeRBounce`、および `brow.spring` の `PhysicsBrowLSpring`／`PhysicsBrowRSpring` です。これらは設定スロットの名前で、実際の出力オブジェクト ID は、永続インスタンスが決めます。

舌出し（`mouth.tongue`）は口形のキャプチャ（`mouth.capture`）に依存し、口の戻り（`mouth.spring`）は下顎（`mouth.jaw`）に依存します。その `dependencies` は、対応するインスタンスを固定します。口腔の奥行き（`mouth.interior-depth`）は、`face/legacy` の提供者を固定します。頬紅／陰鬱（`expression.cheek`／`expression.gloom`）は色のチャンネルを含みます。問い合わせは、実際の色の軸の寄与を出します。口の戻りの物理設定スロットは `PhysicsMouthSpring` です。

皮膚のクリッピング（`face.skin-clip`）は、顔の重ねと前髪の影のマスク関係を足します。首の連動（`face.neck`）は、頭の傾きのバインドと首の影のマスクを足し、`dependencies` で `face/legacy` の提供者を固定します。どちらも既存の素材を再利用し、新しいピクセルは生成しません。マスク寄与の任意の `mask` オブジェクトは、`target`、`before`／`after` の出所 ID のリスト、`before_inverted`／`after_inverted` を含みます。これらは、その項目を実行したときの実際の変更であり、最終結果から逆算はしません。

`hair/chains`、`body.bust`、`body.arms`、`outfit.skirt`、`outfit.ribbons`、`outfit.creature` の追加計画は、`body/legacy` と `face/legacy` の運動提供者を含みます。チェーンのパラメータ、受け持つデフォーマ、物理グループの ID は、キャプチャした素材とインスタンスの身元で決まり、一時的なチェーン番号が変わっても ID は書き換えません。物理寄与の `physics_slot` は、モディファイアの `config.physics` の中の、対応する設定キーです。使えるキーは、実際の寄与を問い合わせて取得してください。

明示的なスタックでの物理の問い合わせ、シミュレーション、再生は、実際に評価するグループを読みます。出所は `modifier` で、古いグローバルな生成フィールドは、効くかどうかを決めなくなりました。チェックポイント化していない生成グループは、所属するモディファイアの設定を通します。`physics_put`、`physics_delete`、`physics_fit`、および古い並べ替えのインターフェースは、これらのグループの制御を拒否し、モディファイアの操作を使うよう示します。普通の設定へチェックポイント化したあとは、普通の物理インターフェースを使い続けます。古い設定フィールドは互換の値として残り、実際の状態は、スタックと物理の問い合わせを基準にします。

### メッシュトポロジ

`canvas_topology`（`request.mode` は `topology`）と、エディタのトポロジ操作は、同じ `canvas_topology` の作者ログです。必須は `state/id/action` です（`id` はメッシュ ID。`vertices` は現在のメッシュの頂点インデックスで、既定は空です）。`anchors` の各項目は、既存頂点 `{vertex}` か自由点 `{x, y}` のどちらかで、`add_points` は自由点だけを受け付けます。拒否するときのエラーコードは `topology_refused` です。インデックスは現在のメッシュから来て、最新の `state` を使わなければなりません。`mesh_get_topology` で、頂点インデックスと静止座標（`add_points` のアンカーが使う、メッシュ自身の空間）、三角形、輪郭辺を読みます。確定が成功したとき、結果の `new_vertices` は、その操作が新しいメッシュの中で新しく作った、または選択として残した頂点インデックスを列挙します（`add_points` は新しい点、`knife` は切断線上の点、`merge` はマージ後の点）。バッチのメンバーはこのフィールドを返しません。`action` は次のとおりです。

| `action` | 入力 | 結果 |
| --- | --- | --- |
| `merge` / `duplicate` / `delete` | `vertices` | 1 点へマージする、頂点とその頂点が完全に覆う面を複製する、削除して穴を補う（頂点は少なくとも 3 つ残します） |
| `connect` | `vertices` がちょうど 2 つ | 2 点のあいだの直線が横切る辺を分割し、新しい辺でつなぎます。すでにつながっている、または直線に沿って新しい辺を切れないときは失敗します |
| `subdivide` / `split` | `vertices` または `edges` | 選択した頂点が関わる辺、または与えた辺を細分化します |
| `knife` | `anchors` | 折れ線に沿って辺を足します（エディタの点を置くツールの「線」）。各アンカーはメッシュの上に落ち、隣り合うアンカーのあいだを辺でつなぎます。アンカーは `{vertex}`、またはメッシュ自身の静止空間の `{x, y}` です |
| `add_points` | `anchors`（`{x, y}`。メッシュ自身の静止空間。`knife` と同じ） | 点ごとに頂点を 1 つ足します。三角形の中では 1 が 3 になり、辺の上では辺を分割し、メッシュの外では最も近い可視の輪郭辺から三角形を 1 つ広げます。既存の頂点の上に落ちた点は無視します。新しい頂点の UV、キーフォーム、ブレンドシェイプ、ウェイトは、周囲の頂点から同じウェイトで得て、そのあとテクスチャを変えずに辺を翻します |
| `delete_edges` | `edges`（`[[a, b], …]`。必ず与えます。`vertices` は、どの辺を消すかを決めません） | 内部の辺は、その四辺形の中でもう 1 本の対角線へ取り替えます（四辺形が静止形、いずれかのキーフォーム、またはブレンドシェイプの中で凸でないときは失敗します）。輪郭辺は、それが属する三角形を消し、使われなくなった頂点も一緒に削除します。頂点が点で接する、または三角形を消し尽くすときは、バッチ全体が失敗します |
| `retriangulate` | `vertices`（空配列はメッシュ全体） | これらの頂点の周りを Delaunay へ辺を翻します（既定ポーズのキャンバス空間で判定します）。翻すのは、テクスチャの写像が変わらず、静止形と各キーフォーム、ブレンドシェイプの中でどれも凸な四辺形だけです。頂点とキーフォームは変わりません。翻せるものがもうないときは変化なしです（`applied:false`。履歴は追加しません） |

操作が適用できないときはエラーを返し、何も書きません。キーフォーム、ブレンドシェイプ、頂点グループ、Glue はトポロジとともに移行し（削除された頂点の Glue の対は削除します）、パスはバインドし直します。対象メッシュに内部造形があり、変更前と一致しているときは、同じノードの中で新しいメッシュに合わせてベイクし直します（ベイクの失敗は編集を拒否せず、造形はメッシュが変わったと報告します）。結果は [ジオメトリ検査](#ジオメトリ検査と試行実行) を通過しなければなりません。アルゴリズムと Cubism のメッシュ編集の対照は、メッシュトポロジを参照してください。

### オブジェクトの複製、検索と置換、ID の変換

`object_paste`（`sources` は `mesh:/warp:/rotation:/part:/parameter:` のハンドル。任意の `parent`、`part`、`parameter_group`、`ids`）は、メッシュ、デフォーマ（サブツリーとその中のメッシュごと）、Part（その子ごと）、パラメータを複製します。新しい ID は、記録するときに決まります（既定は `<ID>_copy`。衝突するときは番号を足します。`ids` で項目ごとに指定できます）。再生の結果は、ID と固定して一致します。複製どうしの親、マスク、Glue、パス、頂点グループは、複製を指すように変わり、複製していないオブジェクトを指す参照は残します。`parent` を与えるとき、メッシュはキャンバス座標で掛け替え、デフォーマは局所座標で掛け替えます。ペーストはオーサード Rig の上で計画します。スイング、シミュレーション、内部造形が生成したオブジェクトはそこにはなく、それらを名指しすると拒否します。サブツリーを複製するときも、それらを連れません。複製は、そのレイヤーのメッシュには数えません。レイヤーでメッシュを探す箇所（ピック、分割、削除のときにメッシュ ID を保つ処理）は、元のメッシュだけを認めます。レイヤーを削除すると、複製も一緒に削除します。複製は自身のメッシュ ID で描画順を設定し、レイヤー全体の変換は複製を動かしません。単独で呼ぶとき、結果の `ids` は、元のハンドルから新しい ID への対応を出し、書き込んだ記録と一致します（リクエストは、キャプチャしたバージョンで新しい ID をすべて固定してから確定します）。`paths` は、メッシュとともに複製した変形パス（`path:<原 ID>` から新しい ID）を出します。バッチのメンバーは、`changed` から新しいハンドルを読みます。

`mesh_duplicate`（`source`、`targets`）は、ソースメッシュのトポロジを、キャンバス位置に従って各対象メッシュへ被せます。UV は、対象自身のテクスチャを取ります。各対象は `canvas_mesh_rebuild` を 1 本書き、キーフォーム、パス、ウェイト、Glue はこれまでどおり移行します。

`object_find`（読み取り専用）は、名称または ID（`field`）で `kinds` の中を探します。`find`/`replace`、`regex`、`match_case`、`prefix`、`suffix`、`swap_sides`（独立した L/R、Left/Right、左/右 の入れ替え）、`standard_ids`（Cubism 2 式の ID を Cubism 3 の標準 ID へ変える）に対応し、各項目の変更前と変更後を返します。`object_rename` は、同じパラメータで一度に確定します。どちらも、オーサード Rig のオブジェクトだけを列挙し、ジェネレーターがログのあとに加えたオブジェクトは、ジェネレーターが付けた名称と ID を残します。ID による改名と `object_convert_id`（`renames`。`target` と新しい `id`）は、どちらも `convert_id` のログ項目を 1 本書きます。それを参照するキーフォーム、ブレンドシェイプ、マスク、Glue、パス、頂点グループ、モーションカーブ、表情、ポーズ、ヒット領域、ユーザーの物理、スイングの対象を、同時に改名します（まだドキュメントに入っていない標準表情も、新しい ID で標準パラメータを見つけます。前髪越しの複製は原本に伴って ID が変わり、複製そのものは変換、ペースト、メッシュの複製ができず、`generated_copy` で拒否します）。そのあとの生成上書きと、延後のパネル編集は、新しい ID で再生します。組み込みの物理、モーションプリセット、表情は、新しい ID で標準パラメータを取り戻します。パラメータスナップショット、各ワークスペースのオーサードのポーズ、タイムラインのブックマーク、隠蔽、凍結は、同じ 1 回の確定で新しい ID へ変わり、取り消し、または変換より前へチェックアウトしたときは、元の ID へ戻します。ID は、英字で始まる 1–64 文字の ASCII の英字、数字、またはアンダースコアでなければなりません。同じ種類での重複、すでに使われているもの、ジェネレーターが所有するオブジェクト（シミュレーション、内部造形、スイングのパラメータ）は拒否し、何も変更しません。ボーンにバインドしたメッシュ、またはウェイトを持つメッシュ、ボーンのパラメータ、ボーンのデフォーマも、同様に拒否します（ボーンはログより前に、元の ID でベイクします）。逆に、ボーンは、ID を変換済みのメッシュや、ログが新規作成したメッシュ（ペーストした複製、キャンバスで新規作成したメッシュ）へ、新しくバインドすることもできません。自動スケルトンは、それらを飛ばします。対象 ID に、削除済みパラメータのモーションカーブ、または表情の項目が残っているときは、変換を拒否し、所在するモーションまたは表情を示します。分割、レイヤーの削除、再生成のとき、基礎段階は変換前の ID でメッシュを保ち、そのあとログ項目が改名します。再生成の三者マージも、生成結果を変換後の ID へ換算します。書き出しの前に、実際に書き出すモデルの ID を検査します。同じ種類（Warp、Rotation、ランタイムの仮想ボーンは共用します。パラメータフォルダ、シミュレーション、衝突体を含みます）の重複は書き出しを拒否し、文字が規則に合わないときは警告だけです。


### テクスチャとテクスチャアトラス

レイヤーのキャンバス矩形（キャンバス単位。小数でもかまいません）と、ラスタのピクセルは、互いに独立です。ネイティブ密度は、キャンバス単位あたりのラスタピクセルです。テクスチャアトラスのタイルは `栅格像素 × 密度 × fit` を保存します。密度の既定は 1 です。予算を超えるとき、ロックしていないタイルは 1 つの fit（≤1）を共用して一様に縮小し、ロックと固定位置が入りきらないときは取り消します（説明はプログラムのログへ書くだけで、ツールの結果には入りません）。ジオメトリはテクスチャアトラスを感知しません。密度、予算、固定位置、並べ替えが変えるのは、タイルとバインドした UV だけで、メッシュは変えません。

- `layer_get_texture`（問い合わせ）。`layer_id`。返すのは `canvas_rect`、整数の外包 `bounds`、`raster`、`native_density`、`override{density,lock,pin}`、`deleted`、`tile`（ページ、テクスチャピクセルの矩形、ラスタピクセルあたりのテクスチャピクセル `scale_x/scale_y`、密度、ロックと固定。透明、または削除済みのレイヤーは null）、および `atlas_fit` です。すべて、同じキャプチャバージョンから来ます。
- `atlas_get`（問い合わせ）。任意の `page` でタイルを絞ります。有効な `budget{page_size,max_pages,padding}`、`fit`、`notices`、`auto`（毎回自動配置するか）、`pages[{index,width,height,tile_count,occupancy}]`、`tiles` を返します。`occupancy` は、メッシュに従って配置したタイルについて、メッシュが実際に使うセルだけを数えます。`notices` は、ページ数が予算を超えること、およびページへ入れるために設定した密度より低く保存しているタイルを指摘します。保存済みの配置は `fit` を保つので、より高い解像度の画像へ替えたあと、この 2 つの事情は `fit` には現れません。テクスチャの編集、およびテクスチャの編集を含む `workspace_apply_edits` の結果にも、`atlas_fit` と `notices` が付きます。`atlas_set_budget` に `atlas_pack` を足すと（置き換えと同じバッチに置けます）、予算の中へ戻せます。
- `atlas_render_page`（読み取り専用のバックグラウンドタスク。`request_id/project_id/state` が必要です）。`page`。任意の `max_size`（64–16384、既定 2048。長辺に合わせて縮小します）。終状態には `revision`、元の寸法とレンダリング寸法、`sha256`、そのページのレイヤーが含まれ、PNG は画像コンテンツとして返します。`job_get/job_wait` で、画像を繰り返し取れます。
- `layer_set_canvas_rect`。`layer_id`、`rect{left,top,width,height}`。レイヤーを移動し、拡大縮小して、キャンバスがその矩形の位置にピクセルを表示するようにします。現在の表示位置から出発する `layer_transform` が 1 回です（回転済みのレイヤーは拒否するので、`layer_transform` を使います）。ピクセル、テクスチャ、生成入力は変わらず、メッシュはキーフォームとともに移動します。どのレイヤーでもかまいません。`layer_get_texture` の `canvas_rect` は、レイヤーが現在表示されている位置です。同じ矩形なら、変化はありません。
- `layer_replace_image`。`layer_id` と、`path`（絶対パス。PNG/WebP/TIFF/BMP。単項目だけ）、`png_base64`、`material_id`（素材ライブラリの中の素材。レイヤーはその素材へ関連付きます）の 3 つのうち 1 つです。任意の `fit`（`stretch` は既定で敷き詰め、`contain` は比率を保って中央へ置き、透明で補います）と `rebuild_mesh`（既定 false）。キャンバス矩形は変わらず、生成入力は最初の置き換えより前のピクセルで凍結し、メッシュ、キーフォーム、バインドは残り、変わるのはタイルと UV だけです。`rebuild_mesh=true` では、新しいピクセルからそのレイヤーのメッシュを生成し直します。作者の編集、または実体化したジオメトリがあるときは拒否します。単項目は、先に状態を照合してからファイルを読みます。バッチのメンバーは `png_base64` だけを受け付け、`path` を与えるとバッチ全体が `invalid_edit` で失敗します。最大は 16MP です。ピクセルが同じなら、変化はありません。
- `atlas_check_placement`（問い合わせ）。`placements` は、1–128 項目の `{layer_id, page?, x?, y?, rotation?, density?}` です。省略したフィールドは、確定済みの値を引き継ぎます。`clear` と、各タイルの目標 `page/x/y/width/height/rotation`、`outside_page`（回転後の外接枠がページを超えるか、ページ番号が予算を超えるか）、`overlaps`（メッシュが交差するタイル）を返します。ほかの確定済みタイル、および今回一緒に検査するタイルと比較し、規則は確定時とまったく同じです。2 つのタイルについて、間隔の分だけ外へ広げた外接枠が交差するときだけ、メッシュが実際に使うセル（間隔を外へ広げたもの）で判断します。どちらもメッシュがなく、回転もしていないときは、矩形で判断します。テクスチャアトラスのビューでドラッグ、拡大縮小、回転するときも、この規則です。`clear` の配置は必ずそのまま着地し、衝突のある配置は、下のコマンドが拒否します。
- `layer_set_pixel_density`。一意な `layer_ids` が 1–128 個、必須の `density`（1/64–16 の連続値。`null` は 1 へ戻します）、任意の `lock`（省略すると元の値を保ちます）。配置がすでにあるとき、各タイルは左上を保ちます。大きくなったあとページを超える、またはほかのタイルのメッシュと交差するときは、`tile_collides` で拒否し、何も変更しません。
- `atlas_set_tile`。`layer_id` と `pin{page,x,y,rotation?}`（テクスチャピクセル。`x` と `y` は、回転していない矩形の左上。`rotation` はタイルの中心を回る任意の度数。既定は 0）、または `null` で空き領域へ入れます。ページ番号は予算の中になければなりません。位置は、配置（`atlasArrangement` 設定）に保存します。自動配置のテクスチャアトラスは、先に現在のレイアウトで保存し（`auto` が false になります）、配置がすでにあるときは、先に現在位置で各タイルを保存し、メッシュの覆い領域を補うので、ほかのタイルは動かず、動かして空いた位置へも押し込みません。回転したあとページを超える位置、またはほかのタイルのメッシュと交差する位置は、`tile_collides` で拒否し、何も変更しません。先に `atlas_check_placement` で検査します。移動と拡大縮小を同時にするとき（対角を動かさない拡大縮小、または 2 つのタイルの位置の入れ替えなど）は、`layer_set_pixel_density` と `atlas_set_tile` を、同じ `workspace_apply_edits` へ入れます。検査するのは、バッチ全体のあとのレイアウトだけで、テクスチャアトラスのビューで「適用」が調整セッションを確定するのと、まったく同じです。
- `atlas_set_budget`。`page_size`（256–16384 の 2 の冪）、`max_pages`（1–64）、`padding`（0–32）、`auto`。少なくとも 1 項目を与え、省略した項目は現在値を引き継ぎます。予算は `atlas` 設定へ書きます。`auto=true` は保存した配置を削除し、そのあとの再構築のたびに、矩形で自動配置します（MaxRects）。`auto=false` は、現在のレイアウトを保存します。
- `atlas_pack`。一度きりの配置をして、結果を保存します（`auto` は false になります）。任意の `shape`。`mesh`（既定）は、各レイヤーの最終メッシュのテクスチャ座標が覆う領域（1 セルは 4 ラスタピクセル。間隔を外に足します）で配置し、矩形は互いに入れ子になれ、各タイルは自分のセルだけを書きます。`rect` は矩形です。占有面積の大きいものから置き、予算のページ数に入らないときは、ロックしていないタイルをすべて、1/64 刻みで一緒に縮小します。任意の `layer_ids` は、これらのタイルだけを動かし、残りは元の位置と元の fit を保ちます。同時に、自動配置の固定位置と、すでに存在しないレイヤーの覆いを消します。同じ入力で再び配置しても、変化はありません。そのあと、メッシュの編集が配置したときの覆い領域を超えると、`atlas_get` の `notices` が、配置し直すよう示します。タイルの `shaped` は、メッシュの覆い領域で配置したことを表します。

6 項目の編集は、いずれもプロセスタスクであり、原子的なバッチのメンバーです。`WorkspaceTextureEdits` の純粋な候補、完全な再構築、CAS の確定を経て、1 回の成功は履歴ノードを 1 つだけ追加し、取り消しは、それ以前のタイルを戻します。終状態は `project_id/state/history_node_id/revision/applied/layers/atlas_fit` です（`geometry_diagnostics` を含むことがあります）。`layers` は、テクスチャが実際に変わったレイヤーです。


## プロジェクトと書き出しのタスク

新しく増えた対話の業務入口も、`workspace_get_operation` が完全な schema を提供します。

| ツール | 共通の挙動 |
| --- | --- |
| `preview_pose` | オーサードのポーズ、および選択したモーションの自動キー打ちを、同じ CAS にします。一時的な再生フレームは、この編集を引き起こしません |
| `preview_playback / preview_playback_get / preview_physics` | プロセスが持つ再生、単発のモーション、アニメーション、マウス追跡、物理の評価です。一時フレームは、プロジェクトのポーズも履歴も書きません |
| `canvas_deform_stroke` | brush/smooth/inflate、複数メッシュ、連結の制限、Glue の維持です。サンプリングはキャンバスピクセルです。edit は画像を残し、deform は厳密なキーフォームを書きます。一筆を、1 回の確定にします |
| `canvas_glue_edit / vertex_group_paint` | Glue のブラシ接続、方向ウェイト、バインド解除、再接続、およびシミュレーションのウェイトのブラシ / グラデーション / 反転です。連続する Glue の順序と、アニメーションのチャンネルを残します |
| `layer_draw_order` | `target` で、0–1000 の明示的な描画順を設定します。`order:null` は、元の生成順、または作者のアニメーション順へリセットします |
| `hit_area_put / hit_area_delete / hit_area_preset` | ドキュメントのクリック領域（`rigEdits.hitAreas`）です。バッチにできるドキュメント編集で、インスペクターと階層ツリーの右クリックメニューと共用します。`put` は `id`（Cubism ID の文字集合。たとえば `HitAreaHead`）で新規作成または変更します。`meshes` は `mesh:<id>` のリストです（新規では必須。与えるときは全体を置き換えます）。`name` は、ホストが問い合わせる名称です（既定は、`HitArea` 接頭辞を外した ID）。`new_id` は改名します。`before_id` は、別の領域の前に並べます（`null` は最後）。`delete` は `id` で削除します。`preset` は、意味に従って Head（顔）と Body（上衣。なければ首、または下衣）を足し、同じ ID がすでにあるものは残します。領域はリストの順で判定し、先に当たったものが優先します。書き出しは、model3.json の `HitAreas`（各項目は ArtMesh が 1 つ。複数メッシュの領域は、同名の複数項目として書きます）と、`.p2lrt` の `HITA` へ書きます。領域がないとき、ランタイムは自動の Head/Body を使います。単項目呼び出しの `changed` は、`hit_area:<id>` で関係する領域を列挙します（改名のときは新旧の ID がどちらも入り、`preset` は新しく足した領域を列挙します） |
| `hit_area_list / hit_area_test` | 問い合わせです。確定済みのキャプチャ 1 回から来ます。`list` は、テスト順に、各領域の既存メッシュと、すでに存在しない `missing` メッシュを返します。`automatic=true` は、領域を何も設定していないことを表します。`test` は、キャンバス上の点 `x, y`（キャンバス単位。y は下向き）と、任意の `pose` のポーズの上で、ランタイムの規則に従ってヒットテストします（メッシュが見え、不透明度が 0 より大きく、三角形がその点を含みます）。`hit` と、当たった領域の `id/name/meshes` を返します。`pose` がモデルにないパラメータを含むときは拒否し、プロジェクトを読み込んでいないときは `invalid_state` です |
| `warp_get_controls` | 確定済みのキャプチャ 1 回から、Bezier の分割数と編集コントロールを読みます。`detail=summary` は制御点を含まず、`points` はアンカー、接線の順にページングします（1 ページ最大 256）。座標は親の局所空間です。`persisted=false` は、コントロールをサンプリング格子から再構築したことを表します。CMO3 が保存するのは、サンプリングしたジオメトリと分割情報だけで、これらの編集コントロールは含みません |
| `mesh_get_topology` | 確定済みのキャプチャ 1 回から、1 つのメッシュ（`target` は `mesh:ID`）のトポロジを読みます。`canvas_topology` がインデックスで指す内容です。`section` は `vertices`（既定。`[索引, x, y]`。メッシュ自身の静止空間）、`triangles`（`[索引, a, b, c]`）、または `outline`（三角形 1 つだけが使う輪郭辺 `[a, b]`）を選び、`offset`/`limit`（1–4096、既定 1024）でページングして `items` に置きます。頂点の数、三角形の数、輪郭辺の数、`bounds` は、常に返します。返した `state` を `canvas_topology` へ渡して、はじめてインデックスが有効です |
| `warp_set_topology` | バックグラウンドで、普通のキーフォームとブレンドのキーフォームを、すべて再サンプリングします。親の局所空間での、実際の表面のサンプリング誤差を返します |
| `warp_bezier_divisions / warp_bezier_anchor / warp_bezier_handle / warp_bezier_reset` | バックグラウンドで、分割数、アンカー、接線を保存するか、リセットします。コントロール座標は親の局所空間です。完全な編集コントロールと、サンプリング格子を永続化します |
| `swing_preview / swing_preview_get / swing_preview_render / swing_preview_commit` | 私的なスイングの下書き、試聴の時計、PNG の観察です。確定は履歴の確定 1 回で、キャンセルは下書きを公開しません |
| `skeleton_draft_open / skeleton_draft_list / skeleton_draft_get / skeleton_draft_edit / skeleton_draft_preview_transfer / skeleton_draft_commit / skeleton_draft_cancel` | ボーン編集ツールと共用する、私的なスケルトンの下書きです。開くときは、自身のポーズの CAS で静止ポーズへ戻り、返る `state` は下書きの系譜です。`edit` は、1–128 項目の型付きの意図（一括変換、複製 / ミラー、細分化 / 解消、尾 / 翼のチェーン、柔軟ボーンのチェーン `flex`（分岐は、1 本の柔軟ボーンの下に複数の柔軟な子ボーンを掛けることです）、まっすぐにする `straighten`（先に関節を原画の中心線へ合わせ、静止は直線で、原画はポーズとして保存します）、ウェイトの描画 / 整理 / 消去 / 移転、`revert` など）で、全部するか全くしないかで下書きを変更します。`commit` は、その系譜の上だけで CAS します。そのあとのポーズやドキュメントの変更、プロジェクトの開き直し、新しい下書きは、いずれも衝突します。変わっていないスケルトンは、履歴ノードを作りません |
| `physics_audition / physics_audition_step / physics_audition_get` | 物理パネルの振り子と共用する、選択したグループの試聴セッションです。ドキュメント、履歴、保存したポーズは変えません。`start` は、ワークスペースのオーサードのポーズ（`values` を重ねられます）で `group_id` を駆動し、そのワークスペースの前のセッションを終わらせます。`target` は振り子の根を −1…1 へドラッグし、`release/reset/reset_peaks/stop` があります。`step` は dt ≤ 0.1 秒で 1–240 歩進め、複製の上で解き、拒否されたリクエストは接頭辞を残しません。`get` は時計を進めません。同じ読み込みの中のドキュメント編集は、そのグループを更新します。プロジェクトを開き直すか、グループを削除したあとのセッションは `stale` です。返る `peaks` は、そのまま `physics_fit` の `observed_peaks` にできます |
| `canvas_visibility / canvas_visibility_get` | `workspace_id/canvas_id/mode`（edit/preview）でアドレスする、局所的なレイヤー / デフォーマの表示と非表示、および solo です。階層ツリーの目と solo と、処理を共用します。`action` は `layers/deformers/all_layers/invert_layers/solo/unsolo` です。変更は `state` を進めますが、ドキュメント、履歴、ほかのキャンバス、書き出しは変えず、変化がなければ進めません。問い合わせは、各キャンバスセッションの表示と非表示、solo の記録、`hidden_layer_ids` を返します。編集したことのないキャンバスは、すべて見えます。書き出しの可視性は、引き続き `object_edit_appearance` が変更します |
| `physics_preset_list / physics_preset_put / physics_preset_rename / physics_preset_delete / physics_apply_preset` | 安定した ID を持つ、グローバルな入力 / 振り子のプリセットライブラリです。ライブラリの変更は `library_state` を使い、適用はバッチにできるドキュメント編集です |
| `simulation_preview / simulation_preview_step / simulation_preview_get / simulation_preview_render` | 私的なリアルタイムのシミュレーション場面です。開始、再開始、停止、明示的な dt のステップはバックグラウンドのセッションで、読み取りとレンダリングは時計を進めません |
| `paint_session_begin / paint_session_list / paint_session_control / paint_session_commit` | 私的な複数筆の下書き、取り消し / やり直し / ジャンプ / スポイト / PNG です。ジェスチャとスポイトはキャンバス単位を使い、下書きはレイヤー自身のラスタです（`width/height` ピクセルが `canvas_rect` を覆います）。確定する前はプロジェクトのピクセルを変えず、確定は履歴の確定 1 回です。`paint_session_begin` が `material_id`（`layer_id` の代わり）を渡すと、素材ライブラリの項目自身のピクセルの上に描きます。結果には `material_id` が付き、ほかの編集の確定はセッションに影響せず、その素材のピクセルが変わったときだけ失効します。確定は `material_set_pixels` を 1 回書き、その素材を表示しているレイヤーも更新します。`rebuild_mesh` は適用しません。`paint_session_control` の `select` アクションは、セッションの選択範囲を変えます（`rectangle` / `ellipse` / `lasso` / `wand` / `quick`。`points` と `radius` で作る一筆の筆で、近い色に沿って縁まで広げ、`tolerance` の既定は 32。または `opaque`。レイヤーの不透明度。`op` で新規、追加、減算、交差。または `all` / `none` / `invert` / `feather` / `expand` / `contract`。あとの 3 つは `radius` を取ります）。そのあとのジェスチャと筆は、選択範囲の中にだけ落ちます。選択範囲は筆の履歴に入らず、`session.selection` はそのラスタのバウンディングボックスです。ジェスチャにはほかに `transform`（`matrix` `[a,b,c,d,e,f]` はキャンバス単位のアフィン写像。選択範囲の中のピクセルを動かします。選択範囲がないときはレイヤーの内容全体です。選択範囲も一緒に動きます。`smooth:false` は、最も近いピクセルでサンプリングします）、`blur`（`opacity` が強さ）、`fill`（選択範囲のバウンディングボックスの中の選択範囲を塗り、選択範囲がないときは、レイヤーの元の領域と、すでに描いた領域を塗ります）、`gradient`（`end_color`、`kind: linear / radial`）があります。ペンキバケツは `contiguous:false` を渡せます。各ジェスチャは `lock_alpha` を渡せ、既存のピクセルだけを変えます（`eraser` / `clear` は受け付けないので、筆で背景色を描きます）。`brush` / `pencil` / `eraser` / `blur` は `symmetry`（`vertical` / `horizontal` / `both`）を渡せ、キャンバスの中心線に沿ってミラーします。ミラーと元の筆は同じ一筆に属し、重なるところは 1 回だけ落ち、エディタの対称と同じです。なげなわは最大 4096 点です |
| `motion_create / motion_duplicate / motion_rename / motion_properties / motion_pose / motion_move_keys / motion_delete_keys / motion_paste_keys / motion_replace_keys / motion_preset / motion_close_loop` | モーションのライフサイクル、ポーズのキー打ち、キーの移動 / 削除 / ペースト / 置き換え、および生成プリセットです。すべて原子的なバッチに参加できます。`motion_close_loop` は、列挙したカーブ（既定は全部）の先頭と末尾をつなぎます。0 と末尾にそれぞれキーフレームがあり、末尾は先頭の値を取ります（循環パラメータは、回転方向に沿って整数周を取り、64 周を超えるとエラーです）。末尾へ入る区間を緩めるとき、末尾は先頭の傾きで入ります。`id` のない `motion_create` / `motion_duplicate` の新しい ID は、タイムラインの補助データがまだ残っている、削除済みのモーション ID を飛ばします。`parameter_create` / `parameter_update` が `repeat: true` にしたパラメータは、キーフレームが範囲を最大 64 周まで超えられ、書き出しは元の値で、ランタイムが巻き戻します（Cubism 5.3 の対象が必要です。それより前の対象は、損失レポートに、範囲を超えたモーションカーブを 1 本ずつ列挙し、ランタイムは切り詰めます）。`cmo3` は `isRepeat` を書きます |
| `motion_set_event / motion_delete_event / motion_events` | モーションの Event（motion3 の `UserData`）の書き込み、移動（`from_time`）、削除です。`motion_events` は読み取り専用で、すべての Event と、メッシュのユーザーデータを列挙します |
| `expression_create / expression_put / expression_update / expression_set_entries / expression_capture / expression_delete / expression_duplicate / expression_move / expression_reset / expression_list / expression_get` | 表情（exp3）です。ポーズ、スナップショット、または現在のパラメータから新規作成し、項目のブレンド方式は `ADD` / `MULTIPLY` / `OVERWRITE`、フェードインとフェードアウト、順序です。編集は原子的なバッチに参加でき、今回書く項目だけを検証します。読み取り結果の `missing` は、Rig にもうないパラメータを列挙します |
| `pose_put / pose_add_group / pose_set_group / pose_delete_group / pose_update / pose_clear / pose_get` | ポーズ（pose3）のグループ、リンクしたパーツ、切り替えのフェードインです。編集は原子的なバッチに参加でき、新しく足したパーツだけを検証します。`pose_get` の `missing_parts` は、Rig にもうないパーツを列挙します。グループは添字でアドレスし、削除すると、そのあとのグループの添字が前へずれます。同じバッチでは、先に変更してから削除するか、大きい添字から削除します |

制御リクエストは、引き続き変更の重複排除と、ワークスペースのトークンの規則に従います。セッションは、別に自分のトークンを持てます。グローバルな物理ライブラリは、独立したライブラリトークンを使います。読み取り、レンダリング、再生フレーム、私的な下書きは、ドキュメントの確定と同じではありません。`kind/job_backed/batchable` の発見メタデータで、実行のしかたを区別します。

GUI と MCP のプロジェクトの保存とオープンは、同じアプリケーション層のライフサイクルインターフェースを呼び、デスクトップアダプタが持つプロジェクトコントローラが実行します。GUI の入口は、先にユーザーへ未保存の変更を確認してから、放棄フラグを明示して渡せます。MCP は確認ダイアログを呼ばず、未保存の切り替えは既定で拒否します。GUI の呼び出しは、開始時のプロジェクトと状態を残し、信頼できるユーザーの身元で確定します。

GUI のフィールド完了は、アプリケーション層のキューの中で非同期に再構築し、確定します。保存は、先にキューへ入った完了を待ちます。拒否された下書きは、保存によって暗黙にプロジェクトを上書きできません。キューが受け取るのは自分の連続した確定だけで、外部の変更やプロジェクトの開き直しは、古い状態を失効させます。問い合わせは確定済みのキャプチャを読み続け、まだ終わっていない画面の下書きは公開しません。`workspace_inspect`、およびスケルトン / モーションの複合問い合わせは、同じ独立セッションを使い、返る状態、モデル、設定、履歴は、同じ 1 回のキャプチャに属します。問い合わせのあいだの確定や開き直しは、この結果へ混ざりません。`scope=preview` は、確定済みのポーズとロックを読みます。アニメーションの評価フレームと、まだ確定していない GUI の値は、そのポーズの代わりにはなりません。一覧の発見は `items/total` と、当てはまる `next` を返します。inspect の結果は `scope` を持ち、対象の詳細は `object` に固定し、空のページングでも作用域を区別できます。`scope=settings` は、既定項目を補った、保存済みの生成設定と、ドキュメントのメッシュ上書きを返します。古い拡張フィールドは、引き続きプロジェクトのドキュメントの中に保存します。

`workspace_apply_edits`、`project_import_psd`、`project_create_artwork`、`project_import_cmo3`、`project_open`、`project_save`、`project_save_as`、`project_export_model`、`project_export_target`、`project_export_psd` は、`{"ok":true,"operation":"...","data":{"id":"job-...","status":"..."}}` を返します。`job_wait` または `job_get` で、`data.result` の中の結果を読みます。プロジェクトの操作は `project_id`、`state`、`history_node_id` を返し、書き出しはファイルと警告を返します。保存が終わったあとの遅れたキャンセルは、completed と、すでに書いた結果を残します。アーカイブを置き換える前のキャンセルは、古いファイルを残します。タスクはアプリケーションが持ち、切断して再接続したあとも問い合わせできます。アプリケーションを再起動したあとは、復元しません。

上記のタスクの能力詳細は、さらに `job_result_schema` を含み、completed の業務結果を記述します。起動と問い合わせの outputSchema は、同時にタスクの状態を記述します。`queued/running/cancelling` の terminal は false で、いずれも result/error はありません。`cancelled` には result/error がなく、`failed` は構造化した error を要求し、`completed` は operation に対応する result を要求します。タスク一覧は `items/total` と、当てはまる next を返し、成功の result は省略しますが、失敗の error は残します。完了結果と、永続した完了マークは、同じ業務検証を使い、確定後のキャンセルも、実際の成功を残します。

`settings_update/layer_mesh_update/layer_classify` も、プロセスタスクのハンドルを返し、`request_id/project_id/state` を要求します。`job_get/job_wait` の `data.result` で、新しい状態と履歴ノードを得ます。3 つの操作は独立したアプリケーションコマンドを共用し、準備と再構築は進捗を報告し、確定前のキャンセル、または並行の衝突は、元のドキュメントと履歴を残します。CAS のあとの遅れたキャンセル、画面の更新失敗、元のリクエストの再試行は、完全な成功の終状態を残します。分類で省略したフィールドは、開始状態を検査したあとのキャプチャモデルの中でマージし、変化がなければ状態を進めず、履歴も追加しません。GUI の連続ドラッグと、分類の文字入力は、セッションが終わるときに 1 回確定します。単層メッシュの一時プレビューは、正式な永続候補の代わりにはなりません。

PSD のインポートと、画像からの作成は、独立したアプリケーション層のインポータを共用します。`project_import_psd` は絶対 `path` を要求します。`project_create_artwork` は、キャンバスの `width/height` と 1–32 個の `layers` を与え、各レイヤーは絶対 `path` と `name` を要求し、任意で整数のキャンバス位置 `x/y`、および列挙の分類 `role/side` を持ちます。座標と列挙の公開 schema は、実際の検証と一致します。どちらも、空のワークスペースから作成するか、読み込み済みのプロジェクトを切り替え、未保存の変更は既定で拒否するので、先に保存するか、明示的に `discard_unsaved:true` を与えます。読む前に状態をキャプチャし、再構築のあと、新しいプロジェクト ID と読み込み世代をインストールします。古い Rig/journal、レイヤー / メッシュ / 描画順の上書き、補助データは、新しいソース画像へ持ち込みません。タスクの成功結果には、新しいソースレイヤーの `layers` ID も含まれます。

CMO3 のインポートは、独立したアプリケーション層のインポータを共用します。GUI の入口は確認のあと、信頼できるユーザーの身元を持って呼び、MCP はダイアログへ入りません。`mode=new` は、新しいプロジェクト ID と読み込み世代を使います。`mode=replace` は、読み込み済みのプロジェクトを要求し、現れなかったオブジェクト、パラメータスナップショット、履歴の注釈を残し、ドキュメントとポーズのリセットは、同じ 1 回の確定で公開します。インポートしたモデルには、PSD の自動 Rig も、モーションプリセットも適用しません。タスクの進捗は、順に、ファイルの読み取り、候補の準備、再構築、確定を覆います。確定前のキャンセルは元のプロジェクトを残し、確定後のキャンセルは完了結果を残します。

同じプロジェクトの中で、同じ `request_id` と、同じ操作、パラメータ、作者の再試行は、元の結果を返します（書き出しは、元のタスクハンドルです）。変えたあとは、新しい ID を使わなければなりません。成功と失敗の結果は直近 512 個を残し、それより前は消去します。消去済みの ID を再試行すると、再び実行します（古い `state` を伴う変更は、衝突で失敗し、二重には適用しません）。この重複排除は、アプリケーションプロセスの中で共有し、MCP 接続には依存しません。タスクの進捗は問い合わせで返り、終わった呼び出しの progress token は引き継ぎません。古い `export`、`export_psd` という名前は、取り除いてあります。

```json
{"request":{"request_id":"export-001","project_id":"project-id-from-inspect","state":"opaque-state-from-inspect","output_directory":"D:/exports/model"}}
```

すべての変更操作は `request_id` を要求します。ワークスペースの変更は、さらに `project_id` と `state` を要求します。`workspace_inspect` は常に現在の状態を返し、読み込んでいないときの状態トークンも含みます。空のワークスペースを作成またはインポートするとき、`project_id` は `null` です。`state` は、読み込み世代と永続バージョンを含む不透明なトークンで、同じ履歴ノードを開き直したあとも変わり、履歴の HEAD を記入することはできません。ドキュメントの書き込みは、自分の確定トークンと `history_node_id` を返します。変化がなければ、ノードは作りません。共有コンテキストはレジストリが公開して検証し、確定側がもう一度検査します。リクエストの待ちが切断しても、プロセスが持つ実行はキャンセルしません。パラメータスナップショットと履歴の注釈は、すでに同じアプリケーションコマンドと永続バージョンを使い、Rig の履歴ノードは追加しません。GUI と MCP の明示的な pose の変更も状態を進め、再生 / 物理の評価フレーム、およびスナップショットのホバーは、状態を進めません。GUI がパラメータを変えるときは、先にローカルで表示し、そのあと順に確定します。確定が着地する前、`scope=preview` は、確定済みのポーズを返したままです。このとき MCP が先にポーズ、またはほかの変更を確定すると、GUI のキューに入った変更は、衝突に従って確定済みのポーズへロールバックします。

`physics_simulate/simulation_simulate` は、起動する前に、リクエストのプロジェクトと状態を照合し、独立した問い合わせセッションを 1 回キャプチャします。そのあとの変更や、プロジェクトの開き直しは、このサンプリングを変えません。completed の `result` は、元のサンプリング診断と、キャプチャ時の `project_id/state/revision` を含みます。`job_get/job_wait` で問い合わせ、`job_cancel` で、較正、静置、フレームごとの求解を中断します。待ちのキャンセルや切断でもタスクは続き、元のリクエストの再試行は、同じタスクを取り戻します。これらは `read_only:true` を保ち、履歴を増やさず、永続バージョンを変えず、ドキュメントのバッチにも入れられません。`simulation_simulate` の `hold/release` は、それぞれ 0–20 秒です。

```json
{"request":{"request_id":"sample-001","project_id":"project-id-from-inspect","state":"opaque-state-from-inspect","id":"simulation-id-from-inspect","hold":0.5,"release":1.5}}
```

## エラーと回復

即時の呼び出しが失敗したとき、ツールは `ok:false` と構造化された `error` を返します。バックグラウンドのタスクが失敗したとき、`job_get` / `job_wait` の問い合わせそのものは引き続き `ok:true` を返すので、`data.status` を確認しなければなりません。`failed` のタスクの `data.error` は同じ構造を使い、成功の `result` はありません。タスクの問い合わせ、一覧、切断後の再接続、元のリクエストの再試行は、いずれも元のエラーを残します。問題を直したあと新しい試行を始めるときは、新しい `request_id` を使います。古い ID を再利用すると、元の結果を得ます。

| `error.code` | 回復の根拠 |
| --- | --- |
| `invalid_request` | 現在の schema を読みます。`field` がフィールドを示します |
| `output_contract` | サービスの実装が、宣言に合わない結果を返しました。`operation/field` が契約の問題を示します。先にプロジェクトまたはタスクの実際の状態を問い合わせます。これをもって、変更がロールバックされたと仮定したり、すぐやり直したりはできません |
| `invalid_argument` / `invalid_edit` | パラメータまたは編集を直します。バッチのエラーは、さらに `edit_index` と `edit_operation` を持ちます |
| `not_found` / `id_conflict` / `owned_by_generator` / `key_incomplete` / `topology_refused` | 区別できる拒否で、`target` を持ちます（`kind:id`。たとえば `parameter:ParamAngleX`、`hit_area:HitAreaHead`）。バッチのメンバーは、さらに `edit_index` と `edit_operation` を持ちます。指したオブジェクト、キー、または項目が存在しない。新しい ID、または変換後の ID がすでに使われている（削除済みパラメータが残したモーションカーブや表情の項目を含む）。オブジェクトがスイング、シミュレーション、内部造形、またはボーンに属し、そのパネルで処理しなければならない。キーフォーム編集のキーに、対象が直接バインドする軸が欠けている。トポロジ操作が、そのメッシュと選択に適用できない。いずれも、変更は確定していません |
| `tile_collides` | 移動、回転、または密度の変更のあと、`layer_ids` の中のタイルが配置位置から押し出されます（ページを超える、またはほかのタイルのメッシュと交わる）。その回の変更は確定していません。`atlas_check_placement` で別の位置にするか、関係する移動を同じバッチに入れます |
| `state_conflict` | プロジェクトを読み直します。エラーは `expected_state` と `actual_state` を持ちます |
| `project_conflict` | 対象のプロジェクトを確認します。エラーは、空にできる `expected_project` と `actual_project` を持ちます |
| `request_id_reused` | 同じ ID が、別の操作、パラメータ、または作者に使われました。新しいリクエスト ID を使います |
| `unsaved_changes` | 先に保存するか、対応している放棄フラグを明示して渡します |
| `workspace_busy` | 進行中の作業が終わるのを待ち、そのあと現在の状態を読みます |
| `io_error` / `permission_denied` | `message` に従い、ファイルパス、アクセス権、またはディスクの問題を調べます |
| `invalid_state` / `operation_failed` | `message` と現在のプロジェクト状態から、業務の前提または実行の問題を診断します |

`cancelled` は独立したタスク状態で、エラーコードではありません。確定が成功したあとの遅れたキャンセル、または更新の異常は、`completed` と確定済みの結果を残します。これをもって、変更がロールバックされたと主張することはできません。

`rig_create_warp` は、キャプチャした候補モデルの中で `mesh:<id>` を解釈し、対象が 1 つであることと、共通の親 Warp を要求します。`id` を省略したときは 1 回だけ割り当てます。明示した `id` は、同じバッチのあとの項目が参照でき、新しい Warp はバッチの `changed` にも現れます。`rows/columns` の既定は 16、範囲は 1–32 で、実際の格子点は親の格子点に揃い、最終的に各軸は 64 を超えません。オブジェクト詳細の中の実際の分割を基準にします。`fit_local` の既定は true で、普通のキーフォームと独立したブレンドシェイプを重ねた保守的な包絡で切り取り、ジオメトリとブレンドシェイプを同時に再マップし、UV、スカラーチャンネル、パス、頂点ウェイトを残します。包絡が親の領域を超えるときはフィットを拒否します。先に親の領域を広げるか、`fit_local:false` で親の領域全体を残せます。自由に置くキャンバスの Warp は、引き続き `canvas_warp` を使います。単項目の終状態 `job_wait/job_get.result` は `target/state/history_node_id` を含みます。確定前の失敗またはキャンセルは新しい Warp を公開せず、確定後の更新失敗または遅れたキャンセルは、成功とハンドルを残します。

## 原子的なドキュメント編集

`workspace_apply_edits` は、外側の `request_id`、`project_id`、`state` を共用します。`edits` の各項目は `{"operation":"...","request":{...}}` で、内側の request にはその単項目ツールの業務フィールドだけを書き、コンテキストのフィールドは繰り返しません。メンバーは、単項目ツールが公開する同じ業務 schema を使います。未知のフィールドと、対応していないメンバーは、候補の編集の前に拒否します。

### ジオメトリ検査と試行実行

`rig_deform`、`keyform_apply`、`rig_edit_structure`、`object_edit_appearance`、`canvas_warp/rotation/glue/topology`、`canvas_deform_stroke`、`path_deform` のジオメトリ作者ログは、アプリケーション層で完全に再構築したあと、正式な CAS の前にジオメトリ検査を受けます。対応する GUI の journal と MCP は同じ規則を使います。バッチが検査するのは最終候補だけで、前の項目が一時的に退化し、あとの項目が修復することを許します。ほかのドキュメント操作は、この規則のために検査範囲を自動では広げません。

`workspace_preview_edits` は、正式な確定と同じ順序の候補準備、再構築、検査を使いますが、投影も CAS も呼ばず、state、履歴、未保存フラグ、ポーズ、リソースは変えません。読み取り専用のバックグラウンドタスクで、引き続き `request_id/project_id/state` を要求し、`job_wait/job_get.result` から `dry_run:true`、入力の `revision`、`candidate_revision`、`would_change`、`would_commit`、新しく作ったオブジェクトの `changed` ハンドル、`diagnostics` を得ます。Warp、Rotation、または Glue を作るときは明示的な `id` を与え、そのあと同じ state の上で `workspace_apply_edits` に同じ edits を確定します。そうしてはじめて、候補の revision を繰り返し比較できます。試行実行はオブジェクト ID を予約せず、そのあとの状態衝突の検査を迂回する権限も与えません。ジオメトリ以外のメンバーは試行実行を受け付けず、対応範囲はその操作の schema を基準にします。

`workspace_preview_regeneration`（簡略ツールセットでは `workspace_call` を通して呼びます）は、同じしかたで任意のドキュメント操作（素材レイヤーの操作を除く）を試行実行し、確定の前に再生成の結果を見るために使います。`checkpoints` は、確定がログへ書くチェックポイントを列挙します（`index` は候補ログの中の位置です。`kind: "regeneration"` は生成結果をユーザーの Rig へマージしたもので、`issues` は `rig_update_generation` の問題と同じ形式です。`kind: "materialized"` は、新しいエントリより前にチェックポイント化した現在のオーサードです）。`added` / `removed` は、候補が増減するオブジェクトのハンドルです。さらに `dry_run:true`、`revision`、`candidate_revision`、`would_change` があります。

検査は、影響を受けたオブジェクトの、親の局所ジオメトリを覆い、普通のキーポイント、ブレンドシェイプのキーポイント、ウェイト制限点の組み合わせを含みます。有限でない座標、不正なトポロジ、新しく増えた面積ゼロの退化は、確定を止め、`geometry_unsafe` と構造化した `diagnostics.violations` を返します。新しく増えた局所の裏返り、および面積が参照三角形の 1% に満たない局所の潰れは、`diagnostics.warnings` に保存し、既定では遮断しません。既存の作者の流れは、意図した折りたたみを許すので、裏返りの数を、そのまま制作の失敗にはできません。すでにある裏返り、退化、潰れは別に数え、新しい欠陥を足していない編集は止めません。表面全体の可逆なアフィンのミラーと圧縮は通過を許し、面積ゼロの変換は引き続き拒否します。トポロジの変更で古い三角形の身元を引き継げないときは、新しいメッシュの参照ジオメトリを使います。混合座標のデカルト積が 16384 を超えるときは、決定的な分散サンプリングへ切り替え、両端の組み合わせを残し、GEOMETRY_SAMPLING_LIMIT では候補を拒否しなくなります。レポートの scope は、大きい組み合わせ領域がサンプリングを使うことを明示します。これは、全部の組み合わせの証明ではありません。

古いプロジェクトでは、前髪越しの複製（`<原件 ID>_SeeThrough`）を、構築のたびに設定に従って生成し直し、直接は編集できません。参照フィールド（`target`、`id`、`layer_id`、`meshes` など）で複製を名指しするドキュメント操作は、すべて `generated_copy` を返します。名称などの自由テキストは数えません（`details.target` が複製、`details.original` が編集すべき原本です）。原本を変えるか、`seeThroughBrows` / `seeThroughEyes` / `seeThroughOpacity` を設定します。`objects` で複製を見るとき、`topologyInfo.layerId` は原本のレイヤー、`topologyInfo.seeThroughOf` は原本です。目は、前髪に窓を開けて透かすようになりました。完成した Rig は、目のレイヤーの逆クリッピングで、それらを覆う前髪を覆い、それらでクリッピングした前髪の複製を 1 枚描きます（`<刘海 ID>_SeeThrough`。`seeThroughOf` は前髪）。これらのマスクは完成した Rig の中にだけあり、前髪の `topologyInfo.seeThroughWindow` がそれらを列挙し、ドキュメントの中の前髪自身のマスクは空のままです。それらを前髪の `masked_by` へ書き戻さないでください。

候補が、開始ドキュメントに対して作者ログを追加しただけであるとき、検査範囲は、追加したコマンドから導きます。コマンドが直接指すメッシュ、Warp、Rotation、それらのすべての子オブジェクト、関係するメッシュの Glue の相手、および未ベイクのスイングと、ベイク済みシミュレーションの対象です（この 2 種類のジェネレーターは、再生のたびに対象を計算し直します）。オブジェクトの作成や掛け替え、メッシュ再構築など、影響を受けるオブジェクトを項目ごとに列挙できないコマンド、およびログ以外のドキュメント内容も同時に変える候補は、すべてのオブジェクトを比較するところへ戻します。範囲の中の分類は、完全な比較と同じです。レポートの `coverage` は、`mode`（`full` または `scoped`）、実際に比較したオブジェクト数 `checkedTargets`、両側のモデルのオブジェクト総数 `totalTargets` を出します。範囲を狭めたとき、`scope` のテキストは、残りのオブジェクトを比較していないことを記します。

診断が覆うのはこれらのサンプリング点だけで、キーポイントのあいだの補間、親の合成変形、Glue、マスク、ピクセルの覆い、物理、視覚的な美しさは証明しません。試行実行の結果は入力バージョンに属し、プロジェクトがそのあと編集されたり開き直されたりしても、結果は新しいバージョンの根拠にはなりません。

正式なジオメトリの作者確定も、`geometry_diagnostics` を返せます。同じ遮断項目と warnings を含みます。ジオメトリ検査がオブジェクトに関わらないときは、このフィールドを省略します。裏返りの警告を、変更のロールバックで表す必要はありません。

面積ゼロの判定は、浮動小数の誤差を含みます。三角形の有向面積の絶対値が `1e-12` に満たない、またはその面積比が参照形の `1e-6` を超えないときは、数値的な退化とみなします。頂点が重なってごく小さい負の面積が残っただけで、裏返りの警告だけを報告することはありません。

GUI のパラメータ定義、フォルダの位置、パラメータのキーポイントは、共有の確定 1 回にまとめられます。キャンバスの journal、内部の typed 編集、フィールド完了のキューも、同じ候補 / 再構築 / CAS の境界へ入ります。GUI のスイングセッション、フィールド編集、シミュレーションの変更、オフラインベイクは、開始時の状態を残し、古い結果を、開き直したプロジェクト、または変わったプロジェクトへ確定しないようにします。GUI の作者は、信頼できるアダプタが `user` と指定します。

パラメータ定義は、今は実際の編集順で journal へ書きます。削除は、それ以前のキーフォームと、最後の既定値を読みます。古いプロジェクトの静的なパラメータ上書きは、互換の読み取りを保ち、履歴は書き換えません。不透明度や色などの、純粋なチャンネルの変更も永続化し、すでに存在して同じキーポイントを繰り返しキャプチャしても、履歴は追加しません。

現在、87 項目がバッチに対応します（素材レイヤーの追加、位置合わせの配置、確定、レイヤー全体の変換、メッシュ再構築、および 6 項目のテクスチャ編集を含みます）。設定、レイヤー分類 / メッシュ設定、ソース画像の多角形 / 連結成分 / 前後分割、および削除 / 復元、パラメータ定義、独立した Warp の作成、Rig の変形 / キーフォーム / 構造 / 見た目 / 頂点グループ、6 種のソース画像のペイント、7 種のスケルトン変更、6 種のモーション変更、4 種のキャンバス編集、パスの put/delete/deform、スイングの put/delete、物理の put/delete/config/fit、ならびに simulation の put/delete/bake/clear_bake と model_apply_preset です。設定、分類、メッシュ設定、ソース画像の分割、画像の配置 / 取り消し、レイヤーのソフト削除 / 復元、独立した Warp、ペイント、物理、シミュレーション、プリセットの単項目呼び出しは、バックグラウンドタスクを使います。バッチは、同じアプリケーション層の候補を直接実行し、分割、ペイント、ベイク、フィットの進捗は、所属するバッチメンバーを含み、準備のあいだにバッチ全体をキャンセルでき、メンバーを入れ子にした単項目タスクにはしません。`classic_front_hair/classic_back_hair` は `sway:false` に対応し、シミュレーションを取り除いたあと、その種類の従来のスイングを閉じます。発見ツールが `batchable:true` を返して、はじめて対応を表します。GUI のフィールド完了は、非同期の候補キューへ接続済みで、生成設定 / 分類 / メッシュの下書きは、共有の純粋な候補へ変換します。

素材レイヤーの追加は、インポートしたモデルと、自分で作った親デフォーマに対応します。適用する候補は、ソースのキャンバスジオメトリを、実際の親の中立座標へ変換し、メッシュ作成の記録を保存します。位置を合わせ直すときは、元の処理ピクセルを使い、その場で作成ジオメトリを置き換え、オブジェクト ID と親は残します。専用のバインド、またはマスクの依存がすでにあるときは、単層の配置を引き続き拒否します。素材レイヤーは、素材の元の解像度を保ちます。透明な縁を切り取ったあと、ピクセルはそのままレイヤーのラスタになり、素材の配置または位置合わせが与えるキャンバス矩形が、レイヤー矩形になります（`canvasUnitsPerPixelX/Y` が実際の比です）。たとえば、1024 ピクセルの素材を 32 単位の領域へ位置合わせすると、32 単位で 1024 ピクセルのレイヤーです。平行移動 / 拡大縮小（明示的なミラーを含み、行と列をひっくり返すだけ）の位置合わせは、補間を通りません。回転を伴う位置合わせだけが、素材自身の密度で外包枠へラスタ化します。素材の一覧とレイヤーは、どちらも元のピクセルを保存し、キャンバス解像度へ縮め戻すことはありません。割り当てる前に、座標、外包枠 16MP、ラスタ 16MP、密度の上限を検査し、準備段階はキャンセルに応答します。GUI の連続配置は、独立したアプリケーションセッションを通り、保存はプレビューを除き、正式な確定を待ちます。

各項目は、前の項目の候補ドキュメントと再構築モデルの上で実行するので、先にパラメータを作り、同じバッチでキーフォーム、またはモーション軌道をバインドできます。キャンバスオブジェクトを作ったあと、そのあとの編集で参照することもできます。あとから参照するオブジェクトは、安定した ID を与えるべきです。任意の ID を省略したとき、結果の `changed` は、生成したオブジェクトの `kind:id` ハンドルを含みます。同じリクエストの再試行は、元のハンドルを返します。

最終の CAS が成功して、はじめてドキュメント、モデル、履歴ノード 1 つを公開します。メンバーの編集または再構築の失敗、確定前のキャンセル、状態の衝突は、いずれも元のワークスペースを残します。変化がないバッチ、および元のドキュメントへ戻るバッチは、state を進めず、履歴も追加しません。メンバー実行のエラーは `invalid_edit` を返し、0 から始まる `edit_index` と `edit_operation` を付けます。schema のエラーは `invalid_request` とフィールドパスを返します。セッションの変更、プロジェクトの切り替え、リソース I/O、読み取り専用の時間シミュレーション（simulation_simulate）、入れ子のバッチは、メンバーになれません。バッチはプロセスタスクのハンドルを返し、`job_wait/job_get` で状態と `data.result` を読みます。成功結果は `project_id/state/history_node_id/revision/applied/edit_count/changed` を含みます。項目ごとの準備とベイクのとき進捗を報告し、確定前のキャンセルは候補を公開しません。成功した CAS のあと、すぐに完全な結果を残し、遅れたキャンセルや更新の失敗でも completed を返します。業務の失敗は、タスクの `error` の中に上記の分類とインデックスを残し、schema のエラーは、タスクを作る前に返します。切断、または待ちのキャンセルはタスクを止めず、元のリクエスト ID の再試行は同じタスクを取り戻します。GUI の内部からの直接呼び出しも、同じコマンド境界を使います。

```json
{
  "request": {
    "request_id": "batch-001",
    "project_id": "project-id-from-inspect",
    "state": "opaque-state-from-inspect",
    "summary": "Create an axis and its motion key",
    "edits": [
      {"operation": "parameter_create", "request": {"parameter_id": "ParamAccessory", "name": "Accessory", "min": -1, "max": 1}},
      {"operation": "motion_put", "request": {"clip": {"id": "accessory", "name": "Accessory", "duration": 1}}},
      {"operation": "motion_set_key", "request": {"id": "accessory", "parameter": "ParamAccessory", "key": {"time": 0, "value": 1}}}
    ]
  }
}
```

## パラメータスナップショットと履歴の注釈

`snapshot_create` は、`values` を省略すると、現在の authored pose を保存します。再生中の評価値を保存する必要があるときは、サンプリング値を明示して渡します。`snapshot_update` は、改名だけ、values の置き換えだけ、または同時の変更ができます。省略したフィールドは、元の値を残します。スナップショットを削除しても、ほかのスナップショットの番号は変わりません。GUI のスナップショット欄と MCP は、中立の編集コマンドを共用し、データはランタイムからキャプチャし、保存と開き直しは、スナップショット ID、番号、パラメータ値、履歴の注釈を残します。

`snapshot_apply` は、ロック済みパラメータの値を残し、スナップショットの中ですでに削除したパラメータを無視し、現在のパラメータ範囲で古い値をクランプします。GUI がスナップショットを適用するしかたは MCP と同じで、タイムラインが自動でキーフレームを打つスイッチには影響されません。タイムラインへ記録するときは、別にモーション編集ツールを呼びます。変化がなければ元の `state` を残し、実際に変更したときは、自分の `state`、`project_id`、`history_node_id` を返します。履歴の注釈が変えるのは、ノードの表示タイトル、説明、非表示のマークだけで、ノードの内容と分岐関係は変わりません。

`preview_set` で省略したパラメータとロックは、ランタイムが確定済みのポーズの中の値を残します。`snapshot_apply` も、確定済みのロックに従い、当たらなかったパラメータは元の値を保ちます。アニメーションの評価フレームと、未確定の GUI の表示値は、マージの基線にはしません。`preview_reset` は、すべての既定値を戻し、ロックを解除します。この 3 つは、独立したアプリケーションコマンドと補助 CAS を共用し、Rig の履歴も自動キーフレームも追加しません。論理的な変化がなければ状態を進めませんが、正しい画面のポーズを戻し、再生を止めることはできます。保存も、ワークスペースごとに、キャプチャした永続ポーズとロックを使い、ワークスペースを複製したポーズはすぐに登録します。アーカイブの中では、`workspace.json` へ投影する、ワークスペースのフィールドです。

## 最小の呼び出し

以下の JSON は、対応するツールのパラメータであり、HTTP や JSON-RPC の外層ではありません。例の ID、`state`、座標は、現在のプロジェクトが返す値へ置き換えなければなりません。

`workspace_inspect`：

```json
{
  "request": {
    "scope": "project"
  }
}
```

レイヤー分類は、先に `workspace_inspect scope=layers` が返す `type/role/side/parameter/switch_id` を読み、必要なものだけ更新します。たとえば、既存の素材を切り替え差分へ変えます。

```json
{
  "request": {
    "project_id": "project-id-from-inspect",
    "request_id": "unique-request-id",
    "state": "opaque-state-from-inspect",
    "layer_id": "actualLayerId",
    "type": "switch",
    "parameter": "expression",
    "switch_id": 1
  }
}
```

この 3 つのフィールドは、UI のレイヤー表に対応します。`parameter_update` が変えるのは Cubism のパラメータ定義だけで、ソースレイヤーの分類や差分の関連は変えません。

必要に応じて問い合わせ、そのあと単一オブジェクトを読みます。

```json
{
  "request": {
    "scope": "objects",
    "query": "hair",
    "limit": 24
  }
}
```

```json
{
  "request": {
    "target": "mesh:actualMeshId"
  }
}
```

`view_render_poses` の、固定したカメラでのポーズ比較です。

```json
{
  "request": {
    "viewport": {
      "mode": "canvas_rect",
      "left": 0,
      "top": 0,
      "width": 1000,
      "height": 1000
    },
    "poses": [
      {
        "ParamAngleX": -30
      },
      {
        "ParamAngleX": 0
      },
      {
        "ParamAngleX": 30
      }
    ],
    "columns": 3,
    "target_long_edge": 1024
  }
}
```

`rig_deform`：

```json
{
  "request": {
    "project_id": "project-id-from-inspect",
    "request_id": "unique-request-id",
    "state": "opaque-state-from-inspect",
    "changes": [
      {
        "target": "warp:actualWarpId",
        "key": {
          "ParamCustom": 1
        },
        "operations": [
          {
            "type": "translate",
            "delta": [
              0.02,
              0
            ]
          }
        ]
      }
    ]
  }
}
```

ここでの `key` は、対象が直接バインドしている関係する軸を、すべて覆わなければなりません。観察ポーズの中の親パラメータを、すべてオブジェクトのバインドへ写してはいけません。終わったあとは、返った新しい `state` を使い、実際のモデルをレンダリングして確認します。

## 状態と履歴

- モデルの履歴を進める書き込み呼び出しには、最新の `state` が必要です。成功すると、そのあとの呼び出しに使える状態を返します。期限が切れているときは、調べ直して編集を調整し、むやみに上書きしません。
- バッチ内の編集は、先に検証と再構築をし、成功したあと確定します。1 回の `rig_deform` / `keyform_apply` は 1–128 件の変更を含められ、1 件の変形は 1–16 個の操作を含められます。
- スイングとシミュレーションが生成したキーフォーム（キーの中に生成パラメータがあり、既定値にないもの）。`rig_deform` と `keyform_apply` の `set` は生成結果の上書きとして記録し、ジェネレーターのあとにマージします。これらのマスへの `copy`、またはそこからの `copy`、`delete`、ジェネレーターの軸に作用する `parameter_keys` は拒否し、エラーの中でジェネレーターを示します。ほかのオブジェクトで生成パラメータを使うとき（キーを打つ、改名、範囲の変更）は、先にそのパラメータをジェネレーターの現在の定義に従ってプロジェクトへ書き、そのあとは普通のパラメータと同じです。ジェネレーター自身のパラメータは、`parameter_delete` では削除できません。
- 普通の、変化のない書き込みは、履歴ノードを作るべきではありません。`checkpoint` は、明示的に点を残す例外です。復元は書き込み操作で、HEAD を動かします。古いノードから編集を続けると分岐ができ、元の分岐は残ります。
- `project_save_as` は、絶対パスで最初の保存、または別名保存の位置を指定します。`project_save` は現在の位置へ保存します。`project_open` はプロジェクトアーカイブを開き、未保存のときは先に保存タスクの成功を待つか、`discard_unsaved=true` を明示します。これらの操作は GUI のダイアログを開きません。`project_export_model` は納品ファイルを書きますが、プロジェクトの保存の代わりにはなりません。一時的に置いた素材は、モデルへ加わったことでも、ディスクへ保存したことでもありません。
- タイムアウトまたは切断のあと、`workspace_inspect` と `history_list` で確定済みかを確認し、そのあと次の手順を決めます。
- 複数ツールにまたがる呼び出しのトランザクション、構造化された `history_diff`、`task_*` の実行制御は、現在の公開インターフェースとしては提供していません。

## 形状、パス、物理

### ボーンとモーション

先に `skeleton_propose` を呼び、現在のレイヤーから推定したスケルトンを検査します。`skeleton_auto` が、それをプロジェクトへ書きます。`skeleton_get` は完全な `spec` を返し、ボーンの座標、階層、アートプリミティブのバインド、関節の角度範囲、関節の帯域を含みます。`skeleton_put` は、返った `spec` で全体を置き換えられます。`bone` は 1 本のボーンのフィールドをマージし、`move` はつながった関節を同時に動かし、`bind` はアートプリミティブを指定したボーンへバインドし（`bone_id` を省略するとバインドを外します）、`remove` は体でないボーンを削除し、`delete` はスケルトン全体を削除します。アートプリミティブの ID は、`workspace_inspect scope=objects` から得ます。書き込みは最新の `state` を使い、成功すると再構築して履歴を確定します。無効なスケルトン、または存在しないアートプリミティブは、書き込みを拒否します。

```json
{"request":{"mode":"propose"}}
```

```json
{
  "request": {
    "project_id": "project-id-from-inspect",
    "request_id": "unique-request-id",
    "state": "opaque-state-from-inspect",
    "bone_id": "actualBoneId",
    "end": "tail",
    "point": [
      420,
      610
    ]
  }
}
```

`skeleton_pose` は、キャンバスピクセル座標でボーンの向きを求めます。`ik: true` は、末端と、最大 2 段の親ボーンの角度を求めます。返る `values` は計算結果で、`view_render_poses` へ渡してレンダリング確認でき（ユーザーのパラメータスライダーは動かしません）、または `motion_set_key`、`motion_pose` を経てモーションのパラメータ軌道へ書けます。スケルトン、キーフォーム、履歴は変えません。ボーンの形状は、すでにパラメータ、デフォーマ、メッシュのキーフォームへベイクしてあるので、形状の修正は引き続き `keyform_apply` / `rig_deform` を使い、物理は `physics_put` などの物理操作を使います。

```json
{
  "request": {
    "bone_id": "actualBoneId",
    "target": [
      560,
      440
    ],
    "ik": true
  }
}
```

`motion_list / motion_get` は、永続した `MotionClip` を読み、`sample` は、指定した時刻にクリップの補間で得たパラメータ値を返します。`seed_builtin` は、生成モーションを、編集できる同名の上書きクリップにします。`put` はクリップを作成するか全体を置き換え、`get` が返す JSON で往復して編集できます。クリップには、長さ、ループ、FPS、フェードインとフェードアウト、パラメータカーブを設定できます。キーは `LINEAR`、`BEZIER`、`STEPPED`、`INVERSE_STEPPED`、および `in` / `out` の制御ハンドルに対応します。`set_key` は指定した時刻にキーを書くか置き換え、`delete_key` と `remove_curve` はキー、または軌道全体を削除します。パラメータは存在しなければならず、キーの値と時刻は、パラメータとクリップの範囲に落ちなければなりません。モーションを書き出すには `settings_update exportMotions` を有効にしなければなりません。生成した基礎モーション（Idle、Blink、Nod、Shake）は、さらに `settings_update motionBasic` を有効にしなければならず、スケルトンのプリセットは、さらに `settings_update motionSkeleton` を有効にしなければなりません。

```json
{
  "request": {
    "project_id": "project-id-from-inspect",
    "request_id": "unique-request-id",
    "state": "opaque-state-from-inspect",
    "clip": {
      "id": "wave_custom",
      "name": "WaveCustom",
      "duration": 2,
      "curves": [
        {
          "parameter": "ParamArmRA",
          "keys": [
            {
              "time": 0,
              "value": 0
            },
            {
              "time": 1,
              "value": 45
            },
            {
              "time": 2,
              "value": 0
            }
          ]
        }
      ]
    }
  }
}
```

### 汎用の形状と物理

`rig_deform` の操作は、`translate`、`scale`、`rotate`、`arc`、`curve`、`landmarks` を含みます。座標は、固定した入力境界の中の正規化の規約を使い、X は右、Y は下、角度は度です。選択と減衰で、作用範囲を制限できます。任意のメッシュの、点ごとの配列は受け付けません。

`keyform_apply op=seed` は、指定したキーで、既存の形状をサンプリングします。`copy` は、明示した出所のキーを複製します。`set` は、チャンネルまたは回転の形状を書きます。`delete` は、対応するキーのデータを削除します。パラメータを新規作成しただけでは動きは生まれず、形状とパラメータのバインドが必要です。

`keyform_apply op=mirror` は、キーフォームを、鉛直なキャンバス線に沿って左右へミラーし、確定するとき普通の `set`（および `copy`）へ展開します。再生は、ミラーを計算し直しません。

| フィールド | 意味 |
| --- | --- |
| `target` | 出所のメッシュ、Warp、または Rotation デフォーマ |
| `destination` | 同じ種類の対象。既定は `target`（同じオブジェクト）。`auto` は、ID または名称の中の L/R、Left/Right、左/右 から、もう一方のオブジェクトを探します |
| `from` / `all_keys` | 出所のキー。または `all_keys: true` で、出所のキーフォームのマスをすべて扱います。同じオブジェクトのときは、`side`（`positive` が既定 / `negative`）の側のキーだけを取ります |
| `key` | 対象のキー。既定は `from` から導きます。左右のパラメータは既定値を中心に反転し、側のあるパラメータ（ParamEyeLOpen）は、対象がもう一方をバインドしているとき、またはどちらもバインドしていないとき、もう一方へ替えます。対象にない軸は既定値を取ります。明示して与えるときは、対象が直接バインドする軸を、すべて含めなければなりません |
| `reflect` | 反転するパラメータ。既定は、範囲が既定値について対称で、かつ 2 次元の関連の横軸であるか、ID が X または Z で終わる、普通のパラメータです |
| `axis_x` | ミラー線のキャンバス x。既定は、2 つのオブジェクトの既定ポーズのバウンディングボックス中心の中点です（同じオブジェクトなら、自身の中心） |
| `channels` | 運ぶ内容。`geometry` と、`copy` のチャンネル名。既定は、ジオメトリだけをミラーします |

ミラーするのは、オブジェクト自身の変形です。2 つのオブジェクトの点を、既定ポーズの親でキャンバスへ写像し、出所の各点が既定形状に対して持つ変位の x を反転したあと、対象の点のミラー位置で、出所の三角形（Warp は格子の対角分割）の上で補間し、対象の親空間へ写し戻します。Rotation デフォーマは、原点の変位をミラーし、角度の変化を反転します。対称なオブジェクト、または対になったオブジェクトは、点と点が対応します。融合変形のキーには対応しません。

`keyform_apply op=reset / interpolate / paste` は、1 つのキーのジオメトリ形状全体（または `vertices` が与える点だけ）を書きます。確定するとき、同様に普通の `set` へ展開し、再生はほかのキーを読みません。ジオメトリの `set` が、ジェネレーターの所有するキーフォームに作用するときは、生成結果の上書きとして記録します。`paste` が書くのは変位です。メッシュは元の形に対して、Warp と Rotation デフォーマは既定キーの形状に対してです。出所は、そのコマンドをコンパイルするときにサンプリングします。MCP のバッチでは、あとの `paste` が、前のメンバーが書いたあとの候補モデルを読みます（そのため、2 回の `paste` では A と B を入れ替えられません。同じバッチでも、2 回に分けて確定しても、2 回目が読むのは 1 回目を書いたあとの結果です。入れ替えるには、先に `copy` で A を空のキー C へ保存し、そのあと B を A へ、C を B へ貼ります。同じバッチの中で、この順にできます）。エディタのペーストは、複製するときに出所を固定し、ペースト前のモデルに従って、すべての `set` を一度に計算します。

| フィールド | 意味 |
| --- | --- |
| `target` | メッシュ、Warp、または Rotation デフォーマ。`paste` のときは出所です |
| `key` | 書き込むキー。対象が直接バインドする軸を、すべて含めなければなりません |
| `vertices` | これらの点だけを変えます（メッシュ頂点、または Warp 制御点の番号）。残りの点は、そのキーの現在の形状を保ちます。Rotation デフォーマには当てはまりません |
| `to`（reset） | `default`（既定）。`along` の中のパラメータが既定値のときの形状。`rest`。メッシュだけ。変形していないメッシュです |
| `along`（reset） | `to: default` と一緒に使います。既定値を取るパラメータ。既定は、キーのすべてのパラメータです |
| `parameter`（interpolate） | どの軸に沿って補間するか。既定は、キーがちょうど 2 つのキーのあいだに挟まる、その軸です。複数あるときは、与えなければなりません。位置に従って、両側の隣り合うキーの形状のあいだを線形補間します |
| `from`（paste） | 出所のキー。出所にない軸は既定値を取り、形状はその位置の補間で読みます |
| `destination`（paste） | 既定は `target`。ジオメトリを書くとき、もう一方のオブジェクトは同じ種類で、三角形（Warp は行と列）が同じで、親も同じでなければならず、そうでなければ拒否します |
| `all_keys`（reset） | `key` の代わりです。オブジェクトの各キーフォームを、既定キーの形状へ戻します（デフォーマの形状を回復します） |
| `ratio`（paste） | 0–1。既定 1。現在のキーが、ペーストする値へ動く割合です。1 より小さいときは混合です |
| `flip`（paste） | `horizontal` または `vertical`。Warp は列または行を反転します。メッシュは、静止位置でミラー対応する頂点を探します（静止形状は、その方向に対称でなければならず、そうでなければ拒否します）。各点は、対応する点の変位を取り、反転します。Rotation デフォーマは、角度の変化を反転します |
| `channels`（paste） | `geometry`、`opacity`、`drawOrder`、`multiplyColor`、`screenColor` の組み合わせ。既定はジオメトリだけをペーストします。チャンネルの値も、`ratio` で混合します |

`keyform_apply op=multi_key` は、複数のオブジェクトの、複数のキーフォームのチャンネルを一度に変更し、各キーフォームについてチャンネルだけの `set` を 1 本に展開します。`targets`（メッシュ、デフォーマ、またはパーツ）。`keys`（部分的なキーのリスト。キーフォームは、そのうちの 1 つに一致しなければなりません）と `ranges`（各パラメータが許すキーの値。そのパラメータの上にないキーフォームは制限しません）。既定は、すべてのキーフォームです。`opacity`（0–1）、`drawOrder`（0–1000。デフォーマでは使えません）、`multiplyColor`、`screenColor`（`[r, g, b]`）。`method` は `overwrite`（既定）、`add`、`multiply`、または `screen` です（描画順は、前の 2 つだけを受け付けます）。結果は、合法な範囲へ制限します。各チャンネルは、自分の軌道がすでにあるキーの上にだけ書き、軌道がまだないときだけ、オブジェクトのジオメトリキーに従って書きます。`keys` / `ranges` は、これらのキーを篩にかけます。書くのは普通のチャンネルキーフォームで、生成結果の上書きとしては記録しません。どのキーフォームも一致しないときは、拒否します。

`path_get / path_list / path_preview / path_put / path_delete / path_deform` の点は、Mesh の局所座標を使い、読み取り専用の問い合わせ、プレビュー、バインド、キーフォームへのベイクに対応します。パスは編集の補助です。互換は[変形パス](../guide/DEFORM_PATHS.md)を参照してください。

物理グループは Cubism の振り子です。`inputs`（`parameter`、`weight` 0–100、`type` は `x` の位置 X、または `angle` の傾き重力、`reflect`）が、1–16 節の `segments`（`length`、`mobility`、`delay`、`acceleration`）を押し、`outputs`（`parameter`、`vertex`、`scale`、`weight`、`reflect`）は、第 `vertex` 節の末端が前の節に対して持つ角度を読み、`scale` は 1 ラジアンあたりのパラメータ値です（Cubism のネイティブランタイムは、変位型の出力の倍率を読まず、この種の出力は常に 0 なので、角度の出力だけを提供します）。書き出す physics3.json と CMO3 は、プロジェクトの計算 FPS を宣言します（既定 60）。`normalization.position/angle` の `min/default/max` は、入力範囲の写像の目標です。

- `physics_put` は、与えたフィールドだけを変更します。既存の ID（生成グループを含む）は現在のグループを基礎にし、新しい ID は「頭と体の入力、1 節の長さ 10、出力なし」を基礎にします。リストのフィールドは全体を置き換えます。`length` は列全体の長さで、各節を比率でスケールし、`mobility/delay/acceleration` と `output_scale` はすべての節 / 出力に作用し、`segment_count` は節の数を調整します。`enabled` だけを与えるときは、そのグループのオンとオフだけです。生成グループは、変更したあと生成版を置き換え、`physics_delete` まで続きます。古い `input_parameter` / `output_parameter` は、引き続き単入力・単出力として読みます。
- 同じパラメータを駆動できる、有効なグループは 1 つだけです。カスタムグループが生成グループの出力を駆動するとき、生成グループは譲ります。`workspace_inspect scope=physics` は、`fps` と、計算順に並べたグループを返し、各グループは `origin`、`enabled`、`active`、`overridden`、`replaced_by`、`issue` を持ちます。
- `physics_config`。`order` は、先に計算するグループ ID を列挙し、残りのグループは元の順で後ろに並びます。Cubism は順に計算し、後ろのグループは同じ 1 ステップの中で、前のグループの出力を読みます。`fps` はプロジェクトの物理フレームレートで、1–240 の整数です。0 は制限なしです（物理は各レンダリングフレームに合わせて計算し、書き出しは `Fps` を宣言しません）。プレビューのフレーム出しは、これでは制限しません。プレビューのフレームレートはユーザー設定で、既定はディスプレイに従います。
- `physics_import`。`path` は physics3.json の絶対パスです。ファイルの中のグループはカスタムグループになり（同じ ID は既存グループを置き換え、生成グループは `physics_delete` まで置き換えられたままです）、ファイルの順で、既存グループのあとに並びます。同じ出力を駆動するほかのカスタムグループは閉じます。ファイルの `Fps` が、プロジェクトの計算 FPS になります。インポートした ID、閉じたグループ、欠けているパラメータを返します。
- `physics_fit`。パネルの応答カーブの標準の揺れ（右へ 1 秒引いてから放す）でそのグループを動かし、各出力の倍率を、ピークがちょうどパラメータ端点の `target`%（既定 100）に達するように調整します。動かない出力は、元の倍率を保ちます。`observed_peaks` を与えるとき（キーは出力の番号 `"0"`、`"1"`…、値は `physics_audition` が返す到達割合。1 がパラメータ端点）は、この実測応答に従ってフィットし、パネルで振り子をドラッグしたあとの「最大値に合わせて倍率を調整」と、候補を共用します。番号が不正、値が非負数でない、またはどの出力も動かないときは、確定前に拒否します。

`physics_put/physics_delete/physics_config/physics_import/physics_fit` は、プロセスタスクのハンドルを返し、いずれも `request_id/project_id/state` を要求します。`job_wait/job_get` で、確定後の状態と結果を読みます。インポート結果には、さらに `imported` と、当てはまる `disabled/missing_parameters/fps` があります。読み取り、フィット、再構築、確定は進捗を報告します。フィットの静置と、フレームごとの求解はキャンセルに対応し、確定前のキャンセルまたは状態の衝突は元のプロジェクトを残し、確定後の遅れたキャンセル、または画面更新の異常は、成功結果を残します。切断はタスクを止めず、元のリクエストの再試行は同じタスクを取り戻します。フィットは原子的なバッチに対応し、前の候補を読みます。ファイルのインポートはバッチに対応しません。すでに目標倍率へ達しているときの再フィットは、履歴ノードを作りません。出力の応答がまったくないグループは、引き続きエラーになります。
- 先に出力パラメータの運動の端点を作り、そのあと物理を接続します。静的なポーズのコラージュは、時間の進行を含みません。`physics_simulate` でステップ入力により振幅、オーバーシュート、安定時間を検査し、全体の動きは `view_sample_motion` を使います（そのネイティブサンプリング環境が使える必要があります）。

## View と空間の対応

View は、モデルデータから PNG をレンダリングし、デスクトップのスクリーンショットには依存しません。`canvas_rect` はキャンバス矩形を与え、`focus_layers` はオブジェクトの周りをフレーミングします。出力ピクセルの大きさと、モデルのキャンバス寸法は、別の概念です。

`view_render_layer/view_render_context/view_render_model/view_render_poses/view_check_coverage` は、それぞれキャプチャしたプロジェクトバージョンを 1 回読みます。複数ポーズの各画像は、そのモデルと見えるレイヤーを共用し、そのあとの編集、開き直し、プロジェクトの切り替えは結果へ混ざらず、すでにキャプチャした問い合わせを失効させません。PNG、空間の対応、revision は一致したままで、画像と空間の参照は、キャプチャしたときのプロジェクトの中に保存します。

- `poses` は、同じバージョン、同じキャンバス矩形の中で 1–9 個のポーズを比べ、ラベル付きのコラージュを 1 枚返します。総寸法の予算は、画像全体に作用します。
- 対比するポーズは、バックグラウンドで、キャプチャした確定済みバージョンからレンダリングし、オーサードのポーズも GUI のパラメータスライダーも読まず、変えません。対比画像を出すために `preview_set` でポーズを切り替えないでください。ユーザーのオーサードのポーズが変わり、`state` が進みます。
- リクエストで与えていないパラメータは、**パラメータの既定値**でレンダリングし、ユーザーの現在のオーサードのポーズではありません。現在のポーズを基準にするときは、先に `workspace_inspect scope=preview` で確定済みのポーズを読み、共有の `parameters` へ入れ、`poses` には差だけを書きます。
- `include_layer_ids` を省略したときは、現在アクティブなキャンバスの局所的な表示と非表示を使います。ユーザーが隠したレイヤー、隠したデフォーマの下のレイヤー、solo の外のレイヤーは、画像に出ません。ユーザーのキャンバスと無関係な、安定した結果が必要なときは、レイヤー ID を明示して渡します（`workspace_inspect scope=layers` から得られます）。
- `view_compare_history` は履歴バージョンを比べます。`view_sample_motion` は時間でサンプリングします。`view_check_coverage` は、指定した矩形と指定したレイヤーの Alpha の覆いだけを測ります。
- 返ったピクセル↔キャンバスの対応で位置を取ります。複数ポーズのコラージュ全体を、1 枚の素材の空間参照として直接は使えません。
- Alpha の覆い、メッシュの診断、ファイルの書き出し成功は、いずれも美術品質の点数ではなく、サンプリングしていないポーズが正常である証明にもなりません。

`view_sample_motion` は読み取り専用のタスクハンドルを返し、同様に `request_id/project_id/state` を要求します。`frames` は 2–32 個の厳密なフレームオブジェクト（`time/parameters`）で、時刻は 0 から厳密に増加し、最長 10 秒です。パラメータを省略したときは、それ以前の値を引き継ぎます。`samples` はモーションの長さの中の 1–9 個の時刻で、`fps` は 15–120（既定 60）です。結果の時刻は、サンプリングのフレームレートで下へ量子化します。タスクを起動する前に、モデル、見えるレイヤー、リソース対象をキャプチャします。そのあとの変更、開き直し、GUI のアニメーションは、サンプリングへ混ざりません。ネイティブ Cubism の評価、画像ごとのレンダリング、コラージュは進捗を報告し、キャンセルに対応します。任意のネイティブランタイムがインストールされている必要があります。

`job_get/job_wait` で終状態の診断と PNG を読み、繰り返し読むと同じ画像が返ります。完了結果は `project_id/state/revision` を含み、すでにあるコラージュの `revisionId` も、同じキャプチャバージョンを指します。ネイティブサンプリングは一時モデルを使い、GUI のリアルタイムアニメーションと物理の状態は変えません。キューに入ったタスクのキャンセルは実行せず、フレームループをキャンセルしたあとはモデルと一時ファイルを解放します。この読み取り専用の操作は、ドキュメントのバッチに対応しません。履歴の対比は引き続き即時の問い合わせですが、同じ 1 回のキャプチャの履歴木を使い、プロジェクトの開き直しは、その対比の根拠を置き換えません。

## 素材と Layout

プロジェクト内の素材ライブラリと、「先に配置してから生成する」流れです。設計と境界は MATERIAL_LAYOUT_WORKSPACE.md を参照してください。ドキュメント操作は、いずれも `workspace_apply_edits` のメンバーになれます。単独で呼ぶときはプロセスタスクのハンドルを返し、`job_wait/job_get` の `data.result` はバッチ結果と同じです（`state/history_node_id/edit_count/changed`）。新しい素材は `material:<id>`、新しいレイヤーは `layer:<id>` として `changed` に現れます。

| ツール | 主なパラメータ | 説明 |
| --- | --- | --- |
| `project_create` | `width`、`height`。任意の `discard_unsaved` | 未生成の空のプロジェクトを新規作成します |
| `project_import_layout` | `path`（マニフェストの `.json`、またはマニフェストを含む `.zip` の絶対パス）。任意の `discard_unsaved` | [Layout マニフェスト](../spec/LAYOUT_MANIFEST.md) に従って、未生成のプロジェクトを新規作成します。画像は元のピクセルのまま `rect` の上に敷き、レイヤーはマニフェストの上下順、グループ、タグ、差分、可視性、不透明度を保ち、同時に素材として登録して関連付けます。結果は `project_create` と同じです |
| `material_import` | `state`、`paths`（絶対パス 1–64 個）。任意の `folder_id`、`as_reference`、`reference_under`（`as_reference` と一緒に使い、モデルの後ろに置き、同じ 1 回の履歴編集です） | 画像（PNG/BMP/TIFF/WebP/JPEG）、PSD（レイヤーグループは子フォルダになり、各レイヤーは位置を記録します）、CMO3 の各 ArtMesh 画像（`project_import_cmo3` で得るレイヤーピクセルと同じ）、SVG（自身の寸法でラスタ化）、Layout マニフェストまたはマニフェストを含む zip（グループは子フォルダになり、各レイヤーは `rect` を既定位置とし、タグを付けます）、またはフォルダ（入れ子の構造を残し、子フォルダは素材フォルダになり、隠し項目とその中のマニフェスト、zip は飛ばします）を読み、履歴ノード 1 つとして素材ライブラリへ加えます。`as_reference=true` のとき、最初の項目を同時に参考図にします。各画像は 16MP を超えません |
| `material_add` | `state`、`staging`。任意の `folder_id`、`as_reference`、`reference_under` | `material_import` が読んだあと、実際に確定するメンバーです。`staging` はプロセス内に一時保存した内容の要約で、通常は直接呼びません |
| `material_list` | 任意の `folder_id`、`recursive`、`query`、`kind`、`unused`、`offset`、`limit`（≤256） | フォルダと項目を列挙します。`query` は名称とタグ（フォルダのタグを含む）に一致し、`references` はそれを使うレイヤー数です。PSD / CMO3 から読んだフォルダは、`origin` にファイル名を持ちます |
| `material_get` | `id`。任意の `include_svg` | 項目またはフォルダの詳細です。項目は、それを使う `layers` を返します。SVG はさらに `vector_size` を持ち、`include_svg` のときは保存した SVG 原文を付けます |
| `material_preview` | `id`。任意の `size`（16–4096、既定 512） | 項目、またはフォルダを合成した PNG 画像を返します |
| `material_folder_create` | `state`、`name`。任意の `parent_id`、`id` | フォルダを新規作成します。`id` は、同じバッチのあとの項目が参照します |
| `material_move` / `material_rename` | `ids` + `folder_id`（null は最上位）/ `id` + `name` | 整理です。フォルダは、自身の中へは移動できません |
| `material_delete` | `ids` | 項目とフォルダ（その中の内容すべてを含む）を削除します。まだ削除していないレイヤーが使っているときは拒否し、レイヤーを列挙します |
| `material_tag` | `ids`。`set`、または `add`/`remove` | 項目とフォルダのタグです。配置するとき、レイヤーのタグへ併合します |
| `material_set_resolution` | `id`、`width`、`height`（一辺 ≤4096） | SVG のテクスチャ解像度です。それを使うレイヤーのキャンバス矩形は変わらず、ピクセルはそれに伴って置き換わります |
| `material_paint` | `state`、`id`、および一筆（`mode` とそのパラメータ）、または `gestures`（1–512 筆。それぞれ `mode` とそのパラメータを含み、順に 1 回の変更とします）。`mode` は `brush/pencil/eraser/bucket/shape/clear`。パラメータは `points`+`radius`+`hardness`、`point`+`tolerance`、`from`+`to`+`shape`+`stroke_width`+`filled`。任意の `color`（RGBA バイト）、`opacity` | 項目自身のピクセル座標（左上が原点）の上に一筆描きます。アルゴリズムは `paint` と同じです。それを使うレイヤーのキャンバス矩形は変わらず、ピクセルはそれに伴って置き換わり、SVG はビットマップになります |
| `material_fork` | `state`、`layer_ids`（1–128） | ほかのレイヤーと素材を共用しているレイヤーへ、それぞれ自分の複製を 1 つ与えます（素材ライブラリでは元の素材の直後）。ピクセルは変わりません。素材のないレイヤーは 1 つ得ます。各レイヤーは 1 つの素材を表示し、それが唯一の出所です。共用素材のレイヤーの上で `source_paint_*` と `paint_session_begin`（`layer_id`）は素材の上に描き、それを使うすべてのレイヤーが同期します。レイヤーのピクセルを変えるほかの編集は、そのレイヤーだけがその素材を使っているときは素材が追従し、そうでなければ複製を 1 つ与え、関連は切れません。`material_unlink` は古い名前です |
| `material_adopt_layers` | `state` | まだ素材のないレイヤーのそれぞれに、素材を 1 つ登録します（`Layers` フォルダ。グループは子フォルダになります）。以前の古いプロジェクトのためで、新しいインポートと編集は自分で登録します |
| `material_set_pixels` | `state`、`id`、`staging` | 素材のペイントセッションが一時保存したピクセルを書きます。`paint_session_commit` が起動し、通常は直接呼びません |
| `material_adjust` | `state`、`id`。少なくとも 1 項目。`flip`（`horizontal/vertical`）、`hue`（±180）、`saturation`/`lightness`/`contrast`（±100） | 先に反転し、そのあと色を調整します。寸法は変わりません。レイヤーと SVG への同期は、上と同じ規則です |
| `material_export_psd` | `state`、`folder_id`、`path`（絶対 `.psd`） | バックグラウンドでフォルダを PSD にします。項目は既定位置（なければ左上から並べます）。子フォルダはレイヤーグループです |
| `material_export_png` | `state`、`id`、`path`（絶対 `.png`） | バックグラウンドで、項目自身のピクセルをそのまま PNG にします（拡大縮小しません）。たとえば、参考図のクローズアップを外部ツールへ渡し、より鮮明なパーツに描きます |
| `layout_set_reference` | `state`。任意の `id`（既定は最初の 1 枚。ないときは新規）、`material_id`（null は除去）、`rect`、`opacity`（0–1、既定 0.5）、`visible`、`rotation`（中心を回る時計回りの角度）、`locked`、`under`（モデルの下に敷く）、`points`（キーフォーム点の配列。各点はパラメータ ID → 値。いずれか 1 つの点で表示します。`[]` は解除）、または単一点の `pin` | 参考図です。項目をキャンバスの上に重ねる（またはモデルの下に敷く）ことで、配置と対照に使います。レイヤーではないので、生成、書き出し、Rig はどれも見えません。新しく設定するとき `rect` を与えなければ、キャンバス内へ等比で拡大または縮小して中央に置き、省略したフィールドは元の値を保ちます。参考の問い合わせとレンダリングは最初の 1 枚を使います。`material_list` は、最初の 1 枚（`reference`）と全部（`references`）を返します |
| `layout_add_reference` | `state`、`material_id`。残りのフィールドは上と同じです | 参考図をもう 1 枚足し、ほかの参考図の上に描きます |
| `layout_order_reference` | `state`、`id`、`index` | 参考図を、描画順の `index` へ動かします（0 が最初に描きます） |
| `reference_set_mode` | `state`、`mode`（`background` / `layer` / `foreground`）。`reference_id` または `layer_id` のどちらか 1 つ | 1 つの参考を、背景、レイヤースタック、または前景へ移します。参考図がレイヤースタックへ入ると参考レイヤーになり、参考レイヤーがスタックを出ると参考図になります。素材、矩形、不透明度、表示、ピン留めは残り、参考図の回転とロックはレイヤーへ持ち込みません |
| `reference_set_opacity` | `state`、`opacity`（0–1）。`reference_id` または `layer_id` のどちらか 1 つ | 参考図、またはレイヤー内の参考図の不透明度を調整します。生成の前後を問いません。1 回のコマンドが履歴を 1 本記録します |
| `material_crop` | `state`、`id`。`rect`（項目のピクセル）、または `canvas_rect`（キャンバス単位。項目は参考図であるか、このキャンバスに配置してある必要があります）。任意の `name`、`new_id`、`folder_id` | 損失なく新しい項目を切り出します。新しい項目は、これらのピクセルがキャンバスのどこにあるかを覚え、`layer_place` が `rect` を与えないとき、ちょうど参考図の上へ戻ります。タグとフォルダは残します |
| `material_set_placement` | `state`、`id`。`canvas_rect`（null は消去）、または `from`（別の項目） | 項目の、キャンバス上の既定位置を設定します。ピクセルは変わりません。外部でクローズアップから生成した高解像度画像は、クローズアップの位置を取り、はみ出したピクセルがより高いテクスチャ密度になります |
| `material_peel` | `state`、`id`（現在の画像）、`revealed`（同じ寸法で、パーツを取り除いたあとの生成画像）。任意の `region`（同じ寸法のマスク。白、または不透明なところがパーツの可能性があります）、`threshold`（[下限, 上限] の色差 0–255、既定 [8, 30]）、`grow`、`core`、`close`（既定 2）、`band`（既定 24）、`color_match`（既定 true）、`keep`（パーツの上の `[x, y]` 点。項目のピクセル。1–64 個）、`compare_scale`（1–8、既定 1）、`name`、`part_id`、`base_id` | パーツを 1 つ剥がし、`id` の位置に置く項目を 2 つ新規作成します。パーツ（`id` 自身のピクセル。不透明度は 2 枚の画像の差から来て、`region` に `grow` を足した範囲に制限し、`core` は収縮したあとのマスクを強制的に併合し、`keep` はこれらの点とつながった塊だけを残し、遠い破片は捨てます）と、その下の画像（パーツの領域だけ `revealed` を取り、周囲の `band` ピクセル幅の、変えていない領域で Lab の色を合わせます）です。パーツの外は元のピクセルを残し、パーツを重ね戻すと元の画像です。画像全体の色調のずれ（各チャンネルの差の中央値）は、差として数えません。生成画像がより小さい寸法から拡大したものであるとき、`compare_scale` が拡大倍率を与え、元の生成寸法の上で比較し、拡大がもたらす線の差は数えません。最も前のパーツから始め、新しい下地の上で次を剥がし続けます |
| `material_draw` | `state`、`id`。任意の `svg`、`edits`、`density`（0.05–16。キャンバス単位あたりのテクスチャピクセル）、`name`、`folder_id` | ベクターでパーツを描くか、描き直します。`id` が空いているときは新規作成します。SVG の `viewBox` が、パーツのキャンバス上の位置です（キャンバス単位）。パスはキャンバス座標を直接書きます。`density` でレンダリングします（省略したときは項目の現在の密度を引き継ぎ、新しい項目は 1。一辺 ≤4096 ピクセル）。Skia がレンダリングできる内容（パス、グラデーション、クリッピング、マスク、`feGaussianBlur` などのフィルタ、不透明度）に加え、`<style>` の中の要素、`#id`、`.class` の規則に対応し、`var(--name)` は描画キットの色を取り、パス上の `data-taper`（端から端まで均等に配った幅の係数。たとえば `"0.1 1 0.2"`）は、ストロークを両端が細くなる塗りつぶしの線にします。ブレンドモードと、カスタムの CSS 変数はレンダリングしません。それを使うレイヤーはピクセルを置き換え、以前古い位置に置いていたものは新しい `viewBox` へ移ります。`edits` は要素の `id` で、現在の描画（または同時に与えた `svg`）を局所的に変更します。`set` は属性を設定し、null は属性を削除し、`text` は `<style>` のテキストを置き換えます。たとえば 1 本のパスの `d`、または 1 つの色だけを変えます。`svg` と `edits` を与えないときは、新しい `density` でレンダリングし直します |
| `layout_set_drawing_kit` | `state`。任意の `colors`（名称 → `#rgb`/`#rrggbb`。マージし、null は削除）、`styles`（クラス規則のスタイルシート）、`defs`（SVG の定義。グラデーション、フィルタ） | すべての描画が共用する色、クラス規則、定義です。`styles` と `defs` は全体を置き換え、描画自身の同名の規則と id が優先します。すべてのベクター項目を元の解像度でレンダリングし直し、変わったものはレイヤーへ同期します。ある描画の色が足りなくなるときは拒否します |
| `reference_sample` | `points`（[x, y] が ≤256 個）、`radius`（キャンバス単位。平均を取ります）、`rect`、`colors`（1–16、既定 6）。少なくとも 1 項目 | 参考図の色を読みます。各点の色と alpha。`rect` の中の不透明ピクセルの主色（明度の暗いものから明るいものへ。割合を付けます）。キットの色を決めるために使います |
| `reference_trace` | `seed`（[x, y]）。任意の `tolerance`（Lab 距離、既定 12）、`compare`（`seed`/`neighbor`）、`simplify`（キャンバス単位、既定 0.6） | 自動選択です。種から、種の色（`neighbor` は隣接ピクセルとの比較で、柔らかい影を越えて線まで行けます）に近い不透明ピクセルへ広げます。外側の輪郭を簡略化した多角形 `polygon` と、それを通る滑らかな閉じたパス `path`（キャンバス座標。描画の下描きに直接使えます）、ならびに面積、バウンディングボックス、平均色、内部の穴の数を返します。`touches_edge` は、参考図の縁まで広がったことを表します。`holes`（0–16）は、さらに最大のいくつかの穴の輪郭 `hole_outlines` を返します。背景から取るときは人物のシルエットです（人物が画像の縁に触れているとき、シルエットは外側の輪郭の中にあります） |
| `reference_snap` | `points`（1–256）。任意の `radius`（キャンバス単位、既定 4） | 点を、`radius` の中で最も近い線の中心へ吸着します（周囲より明らかに暗いピクセルの中で最も近いものを取り、さらにその近くの最も暗いところを取ります）。近くに線がない点は動かず、`snapped` は false です |
| `layer_place` | `state`、`material_id`。任意の `rect`（単一項目）、`at`（[x, y] の中心）、`layer_id`（単一項目の新しいレイヤー ID）、`reference`（`true` / `false` はすべてを参考、またはモデルレイヤーとして配置。省略すると各項目の印に従います） | 項目、またはフォルダ全体を新しいソースレイヤーとして配置し、既存レイヤーの上に描きます。既定は PSD の位置です（PSD キャンバスをプロジェクトキャンバスの中央へ写像し、はみ出すときは等比で縮小し、寸法が同じときは元の位置）。そうでなければ、素材 1 ピクセル = キャンバス 1 単位とし、キャンバス内へ縮めます。ピクセルは再サンプリングしません |
| `layer_tag` | `state`、`layer_ids`（1–128）。`set`、または `add`/`remove` | 意味タグです。部位の語と `left/right` が分類を決め（たとえば `{hair, front}`、`{eye, iris, left}`）、残りはユーザーのタグです。生成済みレイヤーの分類の変化は、`rig_generate` を待ちます |
| `layer_set_variant` | `state`、`layer_id`、`variant`（`none/toggle/switch`）。任意の `parameter`、`switch_id` | 差分の属性です。タグは残します |
| `layer_mark_reference` | `state`、`layer_ids`（1–128）、`reference` | 参考レイヤーにする（`true`）、またはモデルレイヤーへ戻す（`false`）です。参考レイヤーはキャンバスと上下順の中に残りますが、生成は読まず、Rig と書き出しのどちらも含みません。受け付けるのは、未生成、生成待ち、参考レイヤーだけで、生成済みレイヤーはエラーを返します。戻したレイヤーは、生成済みのプロジェクトでは `rig_generate` を待ちます。`workspace_inspect scope=generation` の `reference_layers` が、それらを列挙します |
| `layer_pin_reference` | `state`、`layer_ids`。`points`（キーフォーム点の配列）、または単一点の `pin` | 参考レイヤーをキーフォーム点へ固定します。各点はパラメータ ID → 値です（それぞれパラメータの範囲内。最大 16 パラメータ）。参考は、いずれか 1 つの点で表示し（各パラメータの差が範囲の 0.5% を超えない）、残りのポーズでは隠します。`[]`、`{}`、または null は解除します。元の点を置き換えます |
| `reference_place_grid` | `state`、`material_id`、`x_parameter`。任意の `y_parameter`、`x_values`、`y_values`、`columns`、`rows`、`pixel_rect`、`rect`、`id_prefix` | キーフォームの格子として描いた参考図 1 枚（たとえば 3×3 の頭部角度）を均等にマスへ分け、各マスを素材として切り出して参考レイヤーとして配置し、対応するキーフォームへ固定します。列は `x_values` で左から右、行は `y_values` で上から下で、`view_render_pad` の並びと同じです。値を省略したときはパラメータのキーフォーム値を取り（y は大きいものから小さいものへ。2D pad と同じ）、このとき数は `columns`/`rows` と一致しなければなりません。`y_parameter` がないときは 1 行です。`pixel_rect` は、図の中の格子のピクセル範囲です（既定は全体）。`rect` は、各マスのキャンバス上の位置です（既定は、各マスを等比でキャンバスへ入れて中央に置きます）。レイヤーと素材の ID は `<id_prefix>_<行>_<列>` です。履歴ノードは 1 つです |
| `material_mark_reference` | `state`、`ids`（項目またはフォルダ。1–1024）、`reference` | 項目（フォルダなら、その中のすべての項目）を、`layer_place` が指定していないとき参考レイヤーとして配置するかどうかです |
| `layer_reorder` | `state`、`layer_ids`（1–128）、`to`（`front/back/forward/backward/above/below`）。`above/below` は `target` が必要です | 上下順（つまり基礎の描画順）を調整します。最前または最後へ動かす、動いていないレイヤーを 1 つ越える、または `target` のすぐ上かすぐ下へ動かします。動かしたレイヤーどうしは、元の前後を保ちます。受け付けるのは、ジェネレーターがまだ読んでいないレイヤーだけです（未生成プロジェクトのレイヤー、生成待ちのレイヤー）。生成済みレイヤーはエラーを返し、その描画順は `layer_draw_order` で調整します |
| `rig_generate` | `state`。任意の `refresh_geometry`、`preset_group` | 生成、または再生成します。未生成のプロジェクトは、現在のすべてのレイヤーで構築します。生成済みのプロジェクトは、前回の生成のあと配置したレイヤーと、待っている分類を併合し、三者マージでユーザーの編集を残し、きれいに移行できなかった内容は `quality.regeneration` を参照します。`refresh_geometry` は、現在のピクセルでメッシュを再構築する、生成済みレイヤーを列挙します。省略すると、画像を置き換えたレイヤーの全部で、`[]` は全部残します。明示的なスタックは、バインドしていない素材に対してだけ、選んだグループを足します。既定は `modern-standard` で、`none` は足しません |

未生成のプロジェクト、空のキャンバス、生成待ちのレイヤーは、いずれも普通の編集ワークスペースを使い、メッシュ、デフォーマ、パラメータ、階層、回転、テクスチャの編集に対応し、書き出しは現在のモデルを含みます。`rig_generate` は、タグに従って既存メッシュの上にバインドとパラメータを生成し、メッシュトポロジを保ちます。`refresh_geometry` で明示的に更新するときは除きます。PSD をインポートした新しいプロジェクトも、同様に各レイヤーを素材として登録し、関連付けます。

典型的な流れは、`project_create` → `material_import` → `material_list` → いくつかの `layer_place`（または PSD フォルダ全体の配置）→ `layer_tag` → `layer_reorder` → `rig_generate` → 作者の編集 → さらに `layer_place` → `rig_generate` です。外部ツールがパーツをすでに分けているときは、Layout マニフェストを書き、`project_import_layout` で、配置済みの未生成プロジェクトを 1 ステップで得て、確認したあと `rig_generate` します。

`view_render_model` は、参考レイヤー（`layer_mark_reference`）をモデルの下に敷いて描きます（`reference.mode` が `difference` のときは、モデルだけを比べます）。`view_render_model` にはさらに `grid`（キャンバス単位をいくつかおきに、座標の注釈付きの格子線を描き、密すぎるときは ×2、×5、×10… と広げ、結果の `gridStep` が実際の間隔です）と `outline_material_ids`（キャンバス位置を持つ項目、つまり描画と切り出したクローズアップの、不透明な縁を、それぞれの色でビューの上に描き、id を付けます）があり、座標を読み、描画と参考図を対照するために使います。参考図だけを見るときは、`include_layer_ids: []` と `reference: {mode: under, opacity: 1}` を与えます。

`view_render_pad` は、モデルが一組のキーフォームの上で持つ姿を、注釈付きの図集 1 枚へつなぎ、2D pad 全体を一度に見ます。列は `x_parameter` が `x_values` の上を左から右、行は `y_parameter` が `y_values` の上を上から下です。値を省略したときはパラメータのキーフォーム値を取り（y は大きいものから小さいものへ）、2D pad に属するパラメータで `y_parameter` を与えていないときは、相手を自動で付けます。`columns` / `rows`（`[起, 止]`。両端を含み、上記の値の添字）は、任意の部分矩形を切り出します。たとえば 5×3 の pad で `columns: [1, 3]`、`rows: [0, 1]` を取ります。`show` が各マスの内容を決めます。`model`（モデルだけ）、`reference`（そのキーフォームが表示する参考レイヤー。ここに固定したもの、または固定していないもの）、`under` / `over`（参考をモデルの下に敷く、または `reference_opacity` で上に重ねる）、`difference`（一致するところは黒。各マスが 0–1 の `difference` を出します）、`side_by_side`（モデル | 参考）です。結果の各マスの `imageRect` `[x,y,width,height]` は `canvasRect` へ対応し、示した参考レイヤーを列挙します。最大 64 マスで、`target_long_edge` / `max_bytes` が画像全体を制約します。外部が同じ順で描いた格子の参考図は、`reference_place_grid` で、固定した参考として読み戻せます。

参考図に沿ってパーツを描く流れは、`layout_set_reference` → `reference_sample` で色を取り、`layout_set_drawing_kit` で色と線のクラスを決め → `grid` 付きの `view_render_model` でパーツを拡大して見、`reference_trace` で輪郭を取り、`reference_snap` で線の上の点を補正 → `material_draw`（隠れている部分は形体に沿って完全に描き、原画の欠陥はそのまま写しません）→ `outline_material_ids`、または配置したあと `reference` で対照 → 繰り返し修正 → `layer_place`、`layer_tag` → `rig_generate` → `view_render_poses` で、回転したときに出る部分を検査します。

参考図に沿ってパーツを分ける流れは、`material_import` で参考図 → `layout_set_reference` → `material_crop`（`canvas_rect`）でクローズアップを切り取り → `material_export_png` → 外部で拡大または描き直し → `material_import` → `material_set_placement`（`from` はクローズアップ）→ レイヤーごと。外部が、最も前のパーツを取り除いた画像を生成 → `material_peel` → 新しい下地の上で続ける → `layer_place` → `view_render_model` の `reference`（`difference` で位置合わせを見、`referenceDifference` が平均の差）と `density`（テクスチャ密度のヒートマップ。`texelDensity` が各レイヤーの、キャンバス単位あたりのテクスチャピクセルを列挙）を検査 → `layer_set_canvas_rect` / `layer_transform` で微調整 → `layer_tag` → `rig_generate` → `view_render_poses` でモーションを検査します。

## 素材ワークフロー（asset_*）

`asset_prepare_reference / asset_import_png / asset_register / asset_reprocess` はプロセスタスクのハンドルを返し、いずれも `request_id/project_id/state` を要求します。`job_wait/job_get` の `data.result` から、業務結果と、確定後の `state/project_id/history_node_id` を読みます。参考図、位置合わせのプレビュー、再処理した画像は、繰り返し取得できます。切断、または待ちのキャンセルはタスクを止めず、元のリクエストの再試行は同じタスクを得ます。準備の失敗、確定前のキャンセル、状態の衝突は、素材を公開しません。CAS のあとの遅れたキャンセルは、completed と実際の結果を残します。

4 項目の書き込みは、独立したアプリケーション層の素材セッションを共用します。先に私的な候補を準備し、そのあと補助 CAS の中で、不変のファイルと素材一覧を公開します。実際に素材を追加すると古い state は失効しますが、Rig の revision は変えず、履歴も追加しません。同じ内容と写像を繰り返しインポートするのは、変化なしです。検査と試しの配置は、キャプチャしたドキュメントと素材一覧を使い、GUI の下書きや、あとからの位置合わせは読みません。保存が複製するのは、キャプチャしたときにプロジェクトに属する素材だけです（`workspace.json` の `assetCatalog`）。PNG のインポートは、ピクセルメモリを割り当てる前に、16MP の上限を検査します。

新しく足す素材には、通常 `reference → import → register → preview → add` を使い、必要なときは `place → finalize` です。`project_create_artwork` は、配置した素材からバックグラウンドで新しいプロジェクトを作れ、切り替えるときは未保存の変更を既定で拒否します。`source_split_polygon` は、キャンバスの多角形で内部と残りに分け、`source_split_components` は、現在のメッシュの連結成分に従って既存ピクセルを割り当てます。どちらも、隠れている内容を補って描きません。

`source_get_components` は読み取り専用の問い合わせです。`layer_id` を渡し、キャプチャ時の `project_id/state/revision`、`can_split/count`、コンポーネント中心の `canvas_x/canvas_y` を返します。コンポーネントはソース画像の Y、次に X で並べます。この順で `source_split_components` の `names` に名前を付け、任意で同じ長さの `sides` と、一意な `piece_ids` を使います。名称は、前後の空白を除いたあと空でなく一意でなければならず、数は現在のコンポーネントと一致しなければなりません。側を省略するか `none` を使うと、元のレイヤーを継承します。コンポーネント検出は、問い合わせがキャプチャした現在のメッシュを使います。そのあとの変更は古い問い合わせ結果を変えず、確定にはそのキャプチャ状態を持たせなければなりません。

`source_split_polygon/source_split_components` は `request_id/project_id/state` を要求し、バックグラウンドタスクのハンドルを返します。`job_get/job_wait` の `result` は、新しい状態、履歴ノード、新しいソース画像の `layers` ID を残します。多角形は、キャンバス頂点が 3–32 個と、2 つの `names`（内部、残り）を要求し、両側に見えるピクセルがなければなりません。任意で、一意な `piece_ids` を 2 つです。新しい ID を指定したあと、同じ原子的なバッチのあとのメンバーでそれらを参照でき、バッチの `changed` は、新しいソース画像の `layer:<id>` ハンドルと、生成オブジェクトのハンドルを同時に含みます。準備 / 再構築はキャンセルでき、確定前の衝突または失敗は元のドキュメントを残します。確定後の遅れたキャンセル、または更新の異常は、完全な成功の終状態を残し、元のリクエストは同じタスクを取り戻します。

パーツが元のソースレイヤーに代わります。元のレイヤーはソース画像を離れ（`deletedLayerIds` には入りません）、パーツは普通のソースレイヤーになり、分類、親、可視性、レイヤーごとのメッシュ、描画順の上書きを継承します。分割は 1 回の再生成です。パーツは元のレイヤーの位置で生成に参加し、元のレイヤーの上のユーザーのキーフォーム、チャンネル、ブレンドシェイプ、パス、頂点ウェイト、Glue は、三者マージでパーツへ運ばれます（スイングとシミュレーションが生成した軸は除きます）。そのあとの顔の設定、立ちポーズ、スケルトン、分類の変化も、同様にパーツへ届きます。シミュレーションの対象、ベイクのオフセット、接続の役割は、パーツへ移行します。ログは、分割の `art_primitive` 記録のあとにチェックポイントを 1 つ書き、記録そのものは再生しません。テクスチャアトラスと書き出しは、元のレイヤーを含まなくなります。`layer_restore`（`layer_ids` を省略した、全部の復元を含む）はそれを戻せません。取り消し、または履歴の切り替えで分割前へ戻るだけです。そのあと元のレイヤー、または元のメッシュ ID を参照するリクエストは失敗し、エラーはそれを置き換えたパーツ ID を列挙します。`source_get_components` も同じです。パーツは、さらに分割できます。CMO3 をインポートしたモデルは、引き続き古い規則で元のレイヤーをソフト削除します。記録の形式は[ドキュメント層](../spec/DOCUMENT_LAYER.md#分割の実体化アートプリミティブ記録-art_primitive)を参照してください。普通、分割、新規、および CMO3 をインポートしたメッシュは、単層 / グローバルのメッシュ更新とリセットのとき、順序付きの置き換えでバインドと既存のシミュレーションオフセットを残し、削除済みメッシュの復元を含みます。多角形の分割が生む新しい切断点と、境界頂点の複製は、方向 Glue で、その元の頂点を持つパーツに追従し、切口の両側は接したままです。削除済み、マスク（クリッピング）、チャンネルマスク付き、または普通でないブレンドのソース画像は、分割できません。コンポーネントの問い合わせは実際のテクスチャアドレスを使い、ソース画像の縁の補完、回転、拡大縮小に対応し、編集済みの普通の対象は `can_split:true` を返せます。GUI のメッシュ連結成分の分割ダイアログは、同じアプリケーションコマンドを呼び、検出時の状態を残し、複数レイヤーの決定はユーザーの履歴確定 1 回です。多角形の操作は現在 MCP の入口であり、これをもって GUI になげなわ分割のメニューがあるとは言えません。

`rig_update_generation` は、このプログラムのジェネレーターで Rig を生成し直します（GUI の **モデル → 生成結果を更新** に対応します）。ログにチェックポイントがあるプロジェクトは、チェックポイントを書いたときのジェネレーター出力を残すので、プログラムを更新しても自動では変わりません。この操作は、チェックポイントが保存した生成スナップショット、このプログラムが今同じ入力に対して生成する結果、現在のオーサード Rig を三者マージします。ユーザーが変えていないオブジェクトは新しい出力に従い、ユーザーの変更は残り、きれいに移行できなかった内容は `issues` に列挙します（同様に `workspace_inspect` の `quality.regeneration` へ入ります）。結果は、新しいチェックポイントとしてログの末尾へ追加します。ログの中の、より古い再生成チェックポイントが、このプログラムでマージすると異なる構造になるとき（親、頂点数、キーフォーム軸、またはブレンドシェイプが異なる）、先にこのプログラムでマージし直し、修正を三者マージで、そのあとのチェックポイントと現在のオーサード Rig へ運びます（問題 `remerged`。`target` は `journal:<序号>`。`detail` は構造が異なるオブジェクトを列挙します）。そのあとの編集は残します。リクエストに必要なのは `request_id/project_id/state` だけで、バックグラウンドタスクのハンドルを返し、原子的なバッチのメンバーでもあります。タスクの `result` は `state/project_id/history_node_id/revision/applied`、`updated`、`issues`（各項目は `{kind, target, detail?}`）です。生成結果が同じで、毎回のマージも一致するときは `updated:false` で、履歴ノードは追加しません。チェックポイントのないプロジェクトは、構築のたびにこのプログラムが生成し、CMO3 をインポートしたモデルには生成結果がないので、どちらも更新は不要です（後者は拒否します）。

`source_split_depth` は、GUI の前後分割のメニュー、複数選択、ダイアログに対応します。現在のメッシュの元の `source_id` と、空でない一意な `middle_ids`（`mesh:` 接頭辞を付けず、source を含みません）を渡します。任意の `names` は、後層と前層の、空でない一意な名称 2 つです。任意の `front_layer_id/front_mesh_id/back_layer_id/back_mesh_id/glue_id` で新しい ID を指定します。後層と前層の、新しいレイヤーとメッシュ 2 つで、ソースメッシュとそのレイヤーを置き換えます。両層は元のピクセルを持ち、独立してペイントでき、中間のメッシュは両層のあいだにあります。結果の `layers` は、前層、後層の順です。両層は、ソースメッシュの現在の親、ジオメトリ、チャンネルキーフォーム、ブレンドシェイプ、パス、頂点ウェイトを得ます。後層は、ソースメッシュの Glue、マスク、シミュレーション対象を引き継ぎ、方向 Glue が前層を後層に追従させます。両層は固定した描画順を使い、元の描画順アニメーションを置き換えます。口と、派生した唇は、選んだメッシュだけを複製し、追加の唇は生成しません。前層を消して空にしても、トポロジは残します。CMO3 をインポートしたモデルは古い記録を引き継ぎます。ソースメッシュとそのレイヤーを残し、その上に前層と方向 Glue を複製します（後層は新規作成しません）。

単項目も同様に `request_id/project_id/state` を要求し、タスク終状態の `layers` は新しいレイヤー ID です（CMO3 をインポートしたモデルは、新しい前層だけです）。一意な `front_layer_id/front_mesh_id/back_layer_id/back_mesh_id/glue_id` を指定でき、同じ原子的なバッチのあとのメンバーが、新しいオブジェクトをペイントまたは編集します。バッチの `changed` は `layer:<id>/mesh:<id>/glue:<id>` を返します。GUI は、メニューまたはダイアログを開いたときの状態を残し、補助データの変更も古い確定を失効させます。準備はキャンセルでき、確定後は厳密な終状態を残します。履歴の再生、保存して開き直し、CMO3 の読み戻しは、動きと Glue の接続ウェイトを残します。これは既存の内容を複製するので、隠れている部分は、なおペイントで補う必要があります。

既存の PSD は、`project_import_psd` でローカルの絶対パスから開きます。`source_paint_brush / source_paint_pencil / source_paint_eraser / source_paint_bucket / source_paint_shape / source_paint_clear` の 6 種のペイントは、プロセスタスクのハンドルを返し、`job_wait/job_get` で `data.result` の中の確定状態、履歴ノード、生成ハンドルを得ます。能力詳細は `job_backed:true`、`batchable:true` を標し、`job_result_schema` を提供します。筆、消しゴム、ペンキバケツ、形状は、UI のラスタアルゴリズムを使います。点、半径、線幅はすべてキャンバス単位で、レイヤー自身のラスタの上に落ちます。ラスタ密度（ラスタピクセル / キャンバス単位。[レイヤーごとの寸法](../spec/DOCUMENT_LAYER.md#レイヤーごとの寸法)を参照）が 1 のレイヤーは、これまでと同じです。32 単位の矩形の上に 1024² のラスタを保存しているレイヤーでは、半径 2 のブラシがラスタの上に半径 64 ピクセルの筆致を描き、確定後もラスタは元の解像度を保ち、キャンバス解像度へ縮め戻しません。矩形の外へ描くと、矩形は同じ密度で広がり、不透明ピクセルへ切り取るとき密度は変わりません。完全に消して空にしたレイヤーは、キャンバス解像度で透明ピクセルを 1 つ残します。私的な下書きも、同様にレイヤーのラスタを下地にします。`paint_session_begin` などの結果の `width/height` は下書きのラスタピクセルで、`canvas_rect` はそれが覆うキャンバス矩形 `[left, top, width, height]` です。スポイトは、キャンバスピクセルの中心でラスタを読みます。下書きが 16MP を超えるときは、矩形の周囲の、描ける余白を縮小します。既定の `rebuild_mesh:false` は、既存メッシュとすべてのバインドを残し、レイヤーのメッシュで切り取ります。メッシュの上に落ちたピクセルはレイヤーに残し（素材に関連したレイヤーは素材へ書き、関連するすべてのレイヤーが同期します）、メッシュの外に落ちたピクセルは、そのすぐ上に接する新しいレイヤーへ分けます（名前は元の名前に `+` を足し、自分のメッシュを持ち、同じ親とパーツの下に掛かり、新しい素材として元のレイヤーの素材があるフォルダへ置き、それと関連します）。メッシュの外の消去は効きません。まだメッシュのないレイヤーは切り取りません。レイヤー自身のメッシュが新しい領域を覆う必要があるときは `rebuild_mesh:true` を渡し、レイヤーはすべてのピクセルに従って広がり、キーフォーム、ブレンドシェイプ、パス、頂点グループ、Glue を移行します。どちらのやり方も、1 回の確定が履歴ノード 1 つです。既存のキーフォーム、Warp、Glue は、ペイントを止めなくなりました。`clear`、またはすべてのピクセルの消去は、レイヤーとバインドを残し、最後のレイヤーも空にして描き直せます。レイヤーの削除は `layer_soft_delete` を使います。前後分割の前層は、常にトポロジを残します。普通の編集済みレイヤー、前後の層、CMO3 をインポートしたものの削除 / 復元は、先に編集を再生してから活動オブジェクトを濾し、復元に必要な ID、キーフォーム、バインドを残します。削除のあいだにメッシュ設定を変えても、隠したメッシュの再バインド結果は保存します。古いプロジェクトが初めて実際に削除または復元するときは、新しい候補で身元と生成基線を固定し、既存の編集がレイヤーの復元によって別のメッシュを指すように変わることを避けます。古い履歴ノードは書き換えません。`layer_mesh_update` は単層のメッシュパラメータを変更し、リセットしたあとはグローバル値を継承します。

普通の透明レイヤーは、初めて見えるピクセルを描いたときにメッシュを自動で作り、先に `rebuild_mesh:true` を渡す必要はありません。口の初めてのペイントも、派生した唇と、その生成キーフォームを作ります。単項目とバッチのタスクが完了した結果の `changed` は、新しいオブジェクトの `mesh:<id>` などのハンドルを含み、編集を続けられます。同じリクエストの再試行は同じタスクを取り戻し、重複して作成したり履歴を追加したりしません。完全に消して空にしたときは、再構築フラグを渡しても、メッシュとバインドを残します。

生成した唇は、明示的な再構築、または初めて作成したときに保存した輪郭と色に従い、そのあとメッシュを残すペイントは、派生テクスチャを生成し直しません。GUI が凍結したピクセルと MCP のジェスチャは、独立した `WorkspaceRasterCommands` を通して、ドキュメント候補、再構築、状態検査を共用します。作成データは順序付きのログへ入り、保存して開き直し、書き出しは、現在のテクスチャを解釈し直します。準備の過程で進捗を報告し、キャンセルを検査し、確定前のキャンセルは元のピクセルと履歴を残します。成功した確定のあとの遅れたキャンセル、または更新の失敗でも、完全な完了結果を残します。切断、または待ちのキャンセルは、タスクを止めません。新しいメッシュの単層設定の更新も、同様にドキュメントへ書き、ID、再バインドのパス、ウェイトを残します。

`layer_delete` と `layer_restore` は、どちらもプロセスタスクを返し、原子的なバッチに対応します。`job_wait/job_get` の `result` で、実際の状態、履歴ノード、影響を受けたレイヤーを読みます。`layer_delete` はレイヤーを本当に削除します。ピクセルはプロジェクトを離れ、そのメッシュはログ記録 `layer_delete` を経てオーサード Rig を離れ、それらを参照するマスク、Glue、パス、ウェイトも一緒です。ジェネレーターが読むレイヤーは、同時に生成入力を離れ、そのあとの再生成は、そのメッシュを生成せず、どの枠にも影響しません。それ以前の履歴ノードはそれを残し、`history_checkout` が取り消しです。最後のレイヤーは削除できません。唇の帯、古い左右半分など、独立したソースレイヤーでない行は、引き続き古いやり方で隠します。`layer_restore` が復元するのは、古いバージョンがソフト削除したレイヤーだけです。一意な `layer_ids` を 1–256 個渡せ、省略したときは全部を復元します（分割に置き換えられた元のレイヤーはそこになく、それを指定するとエラーになり、パーツを列挙します）。削除した内容がないときは変化なしで、履歴は追加しません。

グローバルなメッシュ単位は、`settings_update` の `meshUnits` を使います。`DOCUMENT` は、ドキュメントの長辺 2048 ピクセルを参照にし、同じ画像を拡大したあとも同じ密度を使います。`PIXELS` はソースピクセルの長さを使います。どちらのモードでも、単層のパラメータは、選んだ単位の中の長さを表します。古い v1 設定に `meshSpacing` があり単位がないときは `PIXELS` を引き継ぎ、開き直しで古いメッシュが変わることを避けます。新しい設定、生成基線、書き出しは、実際の単位を保存します。GUI の単位スイッチと公開の変更は、同じ候補の再生成とトポロジ移行を使います。`meshTrace` は、輪郭が何を読むかを決めます。`TEXTURE`（新しいプロジェクトの既定）はレイヤー自身のピクセルを読み、最大 alpha に従って、メッシュ単位の最大 4 倍細かい解像度まで縮め、キャンバスピクセルより細い筆致もメッシュの中に残り、頂点間隔は変わりません。`CANVAS` は、キャンバス解像度へ縮小したビューを読みます。このフィールドがない既存設定は `CANVAS` として読み、`CANVAS` はこのフィールドを書き出さず、古いプロジェクトの設定テキストとメッシュは変わりません。`meshWrap`（0–64 メッシュ単位、既定 0）は包むトポロジです。輪郭を描く前に、その値より狭いすき間と凹みを閉じ、まつ毛や毛先などの細かい突起は 1 つの包絡を共用し、描いたピクセルはすべてメッシュの中に残ります。`layer_mesh` の `changes.wrap` はレイヤーごとに上書きし、`inspect` のレイヤーメッシュ設定は実際の `wrap` を返します。値が 0 のときはフィールドを書き出さず、フィールドのない設定は 0 として読みます。詳細はメッシュトポロジを参照してください。

`import` は、既存のローカル PNG の絶対パス、または画像バイトを受け付けます。モデルに Base64 を一字ずつ生成させないでください。`solid_background` を省略すると、ネイティブの Alpha を残します。背景を除く必要があるときは、実際の無地の色を明示して与えます。市松のスクリーンショットは、透明な素材ではありません。

`register` は、画枠、対応するアンカー、または絶対変換を使えます。アンカーの目標位置はキャンバス座標で、生成画像の座標は、元の完全な PNG ピクセルです。ミラーと、等比でない伸縮は、明示して宣言しなければなりません。解像度を上げても、モデルの中の占有を自動で広げてはいけません。

先に試しの配置をし、親、描画順、マスク、動きの余白を検査してから、正式に加え、または代替レイヤーを削除します。すでに独立したバインド、キーフォーム、または位置合わせの完了がある素材は、全体の再配置を拒否することがあり、実際のエラーと Schema を基準にします。

## 関連する実装

- [公開 Schema とアダプタ](../../../src/main/kotlin/io/github/psd2live/application/WorkspaceAuthoringOperations.kt)
- [素材ツール](../../../src/main/kotlin/io/github/psd2live/application/WorkspaceAssetCommands.kt)
- [パスツール](../../../src/main/kotlin/io/github/psd2live/application/WorkspacePathEdits.kt)
- [プロジェクトの保存](../../../src/main/kotlin/io/github/psd2live/project/WorkspaceStore.kt)

本ページはインターフェースを記録します。これをもって [能力の実測](../STATUS.md) の評価を上げることはしません。完全な効果は、実際のモデル、ホスト、タスクのサンプルで確認する必要があります。

### 目の構造の訂正

`module_catalog` の `eye_structure` は、目のコンポーネントと `sourceStamp` を返します。`settings` の `changes.rig.eyeParts` を通し、レイヤー ID とコンポーネント ID で `side`（left/right）、`region`（upper/lower）、`primitive`（line/tuft/side/skin/auto）、`contacts`、または `path` を設定します。コンポーネントを初めて設定するときは `sourceStamp` が必要です。座標は、目幅で正規化し、顔の下方向を正とする、目の局所座標です。コンポーネントまたはレイヤーが null のときはリセットします。原画の要約と一致しない上書きは残しますが、効きません。この編集は通常の設定履歴に入り、プロジェクトとともに保存します。

### 完全なプリセットの切り替えと衣装のシミュレーション

`modifier_catalog.baselines` は、設定ライブラリの中の切り替えられるプリセット ID を返します。内蔵とユーザーのプリセットを含みます。`modifier_switch` は `state` と `group` を受け、現在のプリセットを全体として置き換え、普通の作者の内容は残します。隔離した候補、CAS、履歴ノード 1 つを通ります。旧版と新版はモディファイアグループの設定で、インスタンスを直接作り、同じスタックが評価します。古いプロジェクトの生成と変換は実行しません。生成内容の上書き、またはチェックポイント化済みの能力を安全に移行できないときは、切り替えを拒否します。

`modifier_plan` の `type: "simulation"` は、`preset: "clothing" | "front_hair" | "back_hair"` と組み合わせられ、元の認識と自動ウェイトの流れを呼びます。返るシミュレーション設定には `automatic_weights` が含まれ、ウェイトはインスタンスの評価に従い、作者がすでに持つ同名のウェイトが優先します。追加を確定するときは、バックグラウンドでベイクし、バインドと物理を生みます。計画そのものはベイクしません。古い `groups` と `modifier_add_group` は、既存クライアントとの互換のため、追加の意味を残します。

プリセットライブラリの切り替えられる項目は、`modifier_catalog.presets` が返します（`id`、`name`、空にできる `name_key`）。`modifier_switch.group` は、その中の ID を使います。ユーザーが保存した JSON プリセットと、インポートした JSON プリセットを含みます。`modifier_organize` は `state` と完全な `nodes` 配列を受けます。各ノードは `id`、空にできる `parent`、空にできる `name` を持ち、名前があればフォルダ、名前がなければモディファイアインスタンスへの参照です。編成のレイアウトはドキュメントのトランザクションとともに保存し、実行順は変えません。実際の並べ替えは、引き続き `modifier_move` を使います。`modifier_stack.organization` がレイアウトを返します。

### アルゴリズムプラグインの管理

`plugin_list`、`plugin_inspect`、`plugin_install`、`plugin_enable`、`plugin_disable`、`plugin_uninstall` は、プロジェクトを開いていることを要求しません。一覧以外はバックグラウンドのタスクを返します。インストールの出所、バージョンの固定、リクエストの形式は [アルゴリズムプラグイン](../guide/ALGORITHM_PLUGINS.md) を参照してください。

プラグインの説明の中で、各アルゴリズムの `context_version` は対応するコンテキストのバージョンを表し、`provides` は提供する能力のエイリアスを列挙します。`requires` は、引き続き実行の前置項目を表します。
