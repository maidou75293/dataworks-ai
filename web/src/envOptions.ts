/**
 * DEV/PROD 环境选择器的共享选项与说明文案（REQ-UX-DEFAULTS-1 默认值显性化）。
 *
 * V116 环境维度：DEV=开发（试跑用）、PROD=生产（正式数据）。此前全站下拉只露
 * 裸值 DEV/PROD，新用户不知道默认值打到哪套数据。所有环境选择器一律从这里取
 * 选项与 extra 文案，避免各处口径漂移。
 *
 * 文案被 scripts/e2e/ux-defaults-snapshot-test.py 快照锁定，改动需同步快照。
 */

export type EnvCode = 'DEV' | 'PROD'

/** 环境选项：值保持 DEV/PROD 不变，label 加白话注（开发=试跑用/生产=正式） */
export const ENV_OPTIONS: { value: EnvCode; label: string }[] = [
  { value: 'DEV', label: 'DEV（开发·试跑用）' },
  { value: 'PROD', label: 'PROD（生产·正式数据）' },
]

/** 默认 DEV 的表单项 extra（新建文件等） */
export const ENV_DEV_DEFAULT_HINT = '默认 DEV＝开发环境，试跑不碰正式数据；要跑正式数据选 PROD＝生产环境'

/** 补数场景 extra：补数通常打生产，默认 DEV 需要显式提醒 */
export const ENV_BACKFILL_HINT = '补数默认 DEV（试跑）；要补正式数据请切 PROD（生产），写入的是生产库'

/** 触发场景 extra：默认值预填自文件自身环境 */
export const ENV_FOLLOW_FILE_HINT = '已按文件环境预填：DEV＝开发（试跑用），PROD＝生产（正式数据）'
