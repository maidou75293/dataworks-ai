// REQ-DW-9：编号自动生成的预览规则 —— 前缀_yyyyMMdd_6位随机后缀。
// 与后端 AutoCode.java 一致（后缀字母表去掉易混字符 i/l/o/0/1）；
// 表单预填后只读展示、提交该值（所见即所存）；直连 API/MCP 留空时由后端按同规则生成。
const ALPHABET = 'abcdefghjkmnpqrstuvwxyz23456789'

export function autoCode(prefix: string): string {
  const d = new Date()
  const ymd = `${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, '0')}${String(d.getDate()).padStart(2, '0')}`
  let suffix = ''
  for (let i = 0; i < 6; i++) suffix += ALPHABET[Math.floor(Math.random() * ALPHABET.length)]
  return `${prefix}_${ymd}_${suffix}`
}
