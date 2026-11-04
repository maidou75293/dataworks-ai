/**
 * rle.ts —— 笔刷 mask ↔ RLE 编解码（REQ-UNIFY-6-4）。
 *
 * 逐行对照 LabelStudioWorkbench.vue maskToRle:3633 / rleToMask:3645（只读原件）。
 * 格式权威在前端：mos 后端把 brushlabels 只当白名单类型原样存取
 * （api/label_studio.py _RESULT_TYPES:1111），不解释 rle 内容 —— 语义以本文件为准。
 *
 * 口径（照抄 Vue，不改一个字符）：
 *   - 游程从 0（背景）起算：首像素即置位时 rle[0] === 0；
 *   - maskToRle([]) → [0]（Vue 循环空转后仍 push(count=0)）；
 *   - rleToMask 尾部截断：游程总和超过 w*h 时只填到掩码末尾（i < mask.length 守卫）；
 *     单个游程 ≤0 或 NaN 不写像素但 val 照常翻转。
 */

export function maskToRle(mask: Uint8Array | number[] | Iterable<number>): number[] {
  const rle: number[] = []
  let cur = 0
  let count = 0
  for (const v of mask) {
    const bit = v ? 1 : 0
    if (bit === cur) count += 1
    else { rle.push(count); cur = bit; count = 1 }
  }
  rle.push(count)
  return rle
}

export function rleToMask(rle: number[] | null | undefined, w: number, h: number): Uint8Array {
  const mask = new Uint8Array(w * h)
  let i = 0
  let val = 0
  for (const run of rle || []) {
    for (let k = 0; k < run && i < mask.length; k += 1) mask[i++] = val
    val = val ? 0 : 1
  }
  return mask
}
