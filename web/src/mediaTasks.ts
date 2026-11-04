/**
 * 从媒体库文件生成标注任务 —— BUG-PARITY-13-B 自 mos/platform/frontend/src/mediaTasks.js 回搬。
 * 媒体文件只存一份：任务里写标注地址（audio/video/image），文本文件**读正文**写进 data
 * （老教训 :1526：以前只写 URL，标注页看到一串链接）。
 *
 * 通道适配（唯一与 Vue 原件的差异，别的照抄）：老版 fetch 的是 item.url（同源直连）；
 * 中台侧 item.url 是上游裸相对地址、缺通道前缀（mos.ts:611 字段注释「勿直接用本字段」），
 * 直接 fetch 必 404。所以文本读取走 mosMediaUrl(path) 通道化地址——静态读穿 /media/**
 * 支持 Range（main.py:169），与预览面板同一口径（BUG-PARITY-13-A 文件头 :27）。
 */
import { TEMPLATES } from './annotationTemplates'
import { mosMediaUrl } from './api/mos'

export const TEXT_MAX = 200 * 1024

export type MediaTaskInput = {
  path: string
  kind: string
  ls_url: string
  name?: string
}

async function readText(url: string): Promise<string> {
  let r
  try {
    // 只取前 TEXT_MAX 字节：200MB 的日志/CSV 不该整个下到浏览器再截断。
    r = await fetch(url, { headers: { Range: 'bytes=0-' + (TEXT_MAX - 1) } })
  } catch (e: any) {
    throw new Error('读取文本文件失败：' + (e?.message || '网络错误'))
  }
  if (!r.ok) throw new Error('读取文本文件失败（' + r.status + '）')
  const body = await r.text()
  return body.length > TEXT_MAX ? body.slice(0, TEXT_MAX) : body
}

/**
 * 把媒体库文件转成 LS 任务。
 * - 图片 / 音频 / 视频：任务里写 ls_url（浏览器和容器都能访问）；
 * - 文本：读文件正文写进模板对应的数据字段，避免把 URL 当成正文。
 */
export async function buildMediaTasks(
  templateKey: string,
  items: MediaTaskInput[],
  fields: Record<string, string> | null = null,
): Promise<{ data: Record<string, string> }[]> {
  const t = TEMPLATES[templateKey] || TEMPLATES.text_qa
  const list = items || []
  const tasks: { data: Record<string, string> }[] = []
  for (const m of list) {
    // 优先按**校验通过的 XML 里的字段名**落键（schema.media.field / text_fields），
    // 模板卡只是建议：用户在高级 XML 里把 $image 改成 $photo 后，模板 key 写
    // image 会让每条任务都打不开媒体。没有 schema 时再退回模板/类型约定。
    const fallback = templateKey === 'custom'
      ? (m.kind === 'text' ? 'text' : (m.kind || 'text'))
      : (t.dataKey || 'text')
    const key = (fields && m.kind === 'text' && fields.text)
      || (fields && (fields as Record<string, string>)[m.kind])
      || (fields && m.kind !== 'text' && fields.doc)
      || fallback
    if (m.kind === 'text') {
      const body = await readText(mosMediaUrl(m.path))
      tasks.push({ data: { [key]: body } })
    } else {
      const data: Record<string, string> = { [key]: m.ls_url }
      if (t.prompt) data.prompt = t.prompt
      tasks.push({ data })
    }
  }
  return tasks
}
