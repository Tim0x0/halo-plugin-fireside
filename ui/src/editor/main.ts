/**
 * 围炉前台发布框编辑器：Tiptap 2.x（与 Halo 官方富文本编辑器同内核同版本线）
 * 的 headless 裁剪版，打成 IIFE 供主题侧 fireside.js 以全局 FiresideEditor
 * 调用，无 Vue 依赖。
 *
 * schema 即白名单：只注册下列扩展，能产出的 HTML 仅
 * <p>/<br>/<strong>/<em>/<s>/<code>/<a>，粘贴的花哨内容一律被降级为纯文本，
 * 与瞬间官方编辑器（Tiptap 全家桶）产出的节点子集完全兼容。
 * 链接不自动识别（autolink 关闭），必须经工具栏显式创建。
 */
import { Editor } from '@tiptap/core'
import Bold from '@tiptap/extension-bold'
import Code from '@tiptap/extension-code'
import Document from '@tiptap/extension-document'
import HardBreak from '@tiptap/extension-hard-break'
import History from '@tiptap/extension-history'
import Italic from '@tiptap/extension-italic'
import Link from '@tiptap/extension-link'
import Paragraph from '@tiptap/extension-paragraph'
import Placeholder from '@tiptap/extension-placeholder'
import Strike from '@tiptap/extension-strike'
import Text from '@tiptap/extension-text'

/** 与 fireside.js 的 TAG_CHARS/TAG_AT_CARET 保持一致（官方 TagsExtension 字符集） */
const TAG_CHARS = '[\\u4E00-\\u9FFFa-zA-Z0-9]'
const TAG_AT_CARET = new RegExp('(^|\\s)#(' + TAG_CHARS + '*)$')

export interface CreateOptions {
  element: HTMLElement
  placeholder?: string
  /** 文档内容变化（含撤销/重做）；输入法组词过程中 PM 不派发事务，天然防抖 */
  onUpdate?: () => void
  /** 光标/选区变化（点击、方向键移动），用于联想条与工具栏态刷新 */
  onSelectionUpdate?: () => void
  /** 键盘拦截通道（联想条 ↑↓/Enter/Tab/Esc）；返回 true 表示已消费 */
  onKeyDown?: (event: KeyboardEvent) => boolean
  /** 粘贴文件（如截图）转投九宫格；返回 true 表示已接收 */
  onPasteFiles?: (files: File[]) => boolean
}

export function create(options: CreateOptions) {
  const editor = new Editor({
    element: options.element,
    extensions: [
      Document,
      Paragraph,
      Text,
      HardBreak,
      History,
      Bold,
      Italic,
      Strike,
      Code,
      Link.configure({
        // 链接必须显式创建：关闭输入/粘贴自动识别；编辑态点击不跳转
        autolink: false,
        linkOnPaste: false,
        openOnClick: false,
      }),
      Placeholder.configure({
        placeholder: options.placeholder || '',
      }),
    ],
    editorProps: {
      handleKeyDown: (_view, event) =>
        options.onKeyDown ? options.onKeyDown(event) : false,
      handlePaste: (_view, event) => {
        const files = event.clipboardData?.files
        if (files && files.length && options.onPasteFiles) {
          return options.onPasteFiles(Array.from(files))
        }
        return false
      },
      // 文件拖放由发布卡片的 drop 监听接手（复用九宫格通道），
      // 这里只拦住 PM 默认行为，避免拖图变成插入 URL 文本
      handleDrop: (_view, event) => {
        const files = event.dataTransfer?.files
        return !!(files && files.length)
      },
    },
    onUpdate: () => options.onUpdate && options.onUpdate(),
    onSelectionUpdate: () => options.onSelectionUpdate && options.onSelectionUpdate(),
  })

  /** 光标左侧未完成的 #片段；非空选区/非文本块内返回 null */
  function getTagFragment(): string | null {
    const { selection } = editor.state
    if (!selection.empty) {
      return null
    }
    const $from = selection.$from
    if (!$from.parent.isTextblock) {
      return null
    }
    // hardBreak 以 \n 代入，保证「换行后紧跟 #」也被识别为行首
    const before = $from.parent.textBetween(0, $from.parentOffset, '\n', '\n')
    const match = before.match(TAG_AT_CARET)
    return match ? match[2] : null
  }

  return {
    /** 富文本 HTML（发布 payload 用；空文档返回 '<p></p>'，配合 isEmpty 判空） */
    getHTML(): string {
      return editor.getHTML()
    },
    /** 纯文本投影：段落间 \n、硬换行 \n，供标签解析（与 textarea 语义对齐） */
    getText(): string {
      return editor.getText({
        blockSeparator: '\n',
        textSerializers: { hardBreak: () => '\n' },
      })
    },
    isEmpty(): boolean {
      return editor.isEmpty
    },
    focus(): void {
      editor.commands.focus()
    },
    setEditable(editable: boolean): void {
      editor.setEditable(editable)
    },
    clear(): void {
      editor.commands.clearContent(true)
    },
    destroy(): void {
      editor.destroy()
    },

    /** 从 textarea 迁移已敲的纯文本（双换行分段、单换行硬换行） */
    setPlainText(text: string): void {
      if (!text) {
        editor.commands.clearContent(true)
        return
      }
      const paragraphs = text.split(/\n{2,}/).map((block) => {
        const inline: Array<Record<string, unknown>> = []
        block.split('\n').forEach((line, index) => {
          if (index > 0) {
            inline.push({ type: 'hardBreak' })
          }
          if (line) {
            inline.push({ type: 'text', text: line })
          }
        })
        return inline.length
          ? { type: 'paragraph', content: inline }
          : { type: 'paragraph' }
      })
      editor.commands.setContent({ type: 'doc', content: paragraphs }, true)
    },

    // ===== 工具栏 =====
    toggleBold(): void {
      editor.chain().focus().toggleBold().run()
    },
    toggleItalic(): void {
      editor.chain().focus().toggleItalic().run()
    },
    toggleStrike(): void {
      editor.chain().focus().toggleStrike().run()
    },
    toggleCode(): void {
      editor.chain().focus().toggleCode().run()
    },
    undo(): void {
      editor.chain().focus().undo().run()
    },
    redo(): void {
      editor.chain().focus().redo().run()
    },
    canUndo(): boolean {
      return editor.can().undo()
    },
    canRedo(): boolean {
      return editor.can().redo()
    },
    isActive(name: string): boolean {
      return editor.isActive(name)
    },
    /** 选区文字（空选区返回 ''），供链接按钮三态判定 */
    getSelectedText(): string {
      const { from, to } = editor.state.selection
      return editor.state.doc.textBetween(from, to, ' ')
    },
    /** 给当前选区套链接（协议校验由调用方负责） */
    setLinkOnSelection(href: string): void {
      editor.chain().focus().setLink({ href }).run()
    },
    /** 空选区处插入「带链接的文字 + 尾随空格」，空格不带链接终止延伸 */
    insertLinkText(href: string, text: string): void {
      editor
        .chain()
        .focus()
        .insertContent([
          { type: 'text', text, marks: [{ type: 'link', attrs: { href } }] },
          { type: 'text', text: ' ' },
        ])
        .run()
    },
    /** 光标处于链接内时整段取消该链接 */
    unsetLink(): void {
      editor.chain().focus().extendMarkRange('link').unsetLink().run()
    },

    // ===== 标签联想 =====
    getTagFragment,
    /** 补全光标处片段为完整标签并带终止空格；无片段时返回 false */
    applyTagSuggest(tag: string): boolean {
      const fragment = getTagFragment()
      if (fragment === null) {
        return false
      }
      const pos = editor.state.selection.from
      editor
        .chain()
        .focus()
        // 传 JSON 文本节点而非字符串：字符串会按 HTML 解析，尾随空格可能被吞
        .insertContentAt(
          { from: pos - fragment.length, to: pos },
          { type: 'text', text: tag + ' ' },
        )
        .run()
      return true
    },
    /** 删除正文中所有已生效的 #tag（含其后一个终止空格），供 chip 的 × 联动 */
    removeTag(tag: string): void {
      const ranges: Array<{ from: number; to: number }> = []
      const pattern = new RegExp('(^|\\s)#' + tag + '(\\s|$)', 'g')
      editor.state.doc.descendants((node, pos) => {
        if (!node.isText || !node.text) {
          return
        }
        pattern.lastIndex = 0
        let match: RegExpExecArray | null
        while ((match = pattern.exec(node.text)) !== null) {
          const start = pos + match.index + match[1].length
          // 终止空格一并删除；行尾/文末命中时 match[2] 为空串
          ranges.push({ from: start, to: start + 1 + tag.length + match[2].length })
          // 允许 "#a #a" 连续命中：回退一格让下一轮从终止符重新匹配
          if (match[2]) {
            pattern.lastIndex -= 1
          }
        }
      })
      if (!ranges.length) {
        return
      }
      const chain = editor.chain().focus()
      for (let i = ranges.length - 1; i >= 0; i--) {
        chain.deleteRange(ranges[i])
      }
      chain.run()
    },
  }
}

export type FiresideEditorInstance = ReturnType<typeof create>
