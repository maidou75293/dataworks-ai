import { useEffect, useState } from 'react'
import {
  Button, Divider, Input, InputNumber, Popconfirm, Select, Space, Switch, TimePicker,
  Typography, message,
} from 'antd'
import dayjs from 'dayjs'
import { api, del, post, put } from '../api'
import { can } from '../permissions'
import type { Workflow } from '../types'

type BaselineMember = { workflowId: number; code: string; name: string }

type BaselineRow = {
  id: number
  name: string
  deadlineTime: string
  warnBeforeMin: number
  level: string
  owner?: string | null
  enabled: boolean
  memberCount: number
  members: BaselineMember[]
}

type Draft = {
  name: string
  deadlineTime: string
  warnBeforeMin: number
  level: string
  owner: string
  enabled: boolean
}

const DEFAULT_DRAFT: Draft = {
  name: '',
  deadlineTime: '07:00',
  warnBeforeMin: 30,
  level: 'P2',
  owner: '',
  enabled: false,
}

/**
 * 任务基线配置（REQ-DW-11）：放在任务编辑右侧「配置」面板里。
 * 一条基线 = 承诺完成时间 + 提前预警窗口；这里完成新建、修改、停用与（仅含当前任务时的）删除，
 * 基线菜单页因此可以做成纯列表。基线含多个任务时，此处改动作用于共享的这条基线。
 */
export default function BaselineConfig({ workflow }: { workflow: Workflow }) {
  const writable = can('ops:run')
  const [row, setRow] = useState<BaselineRow>()
  const [draft, setDraft] = useState<Draft>(DEFAULT_DRAFT)
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [note, setNote] = useState('')

  // 只在挂载时拉一次：切换任务时由父级用 key={workflow.id} 重新挂载，避免输入改名触发重载
  useEffect(() => {
    let alive = true
    setLoading(true)
    api<BaselineRow[]>(`/api/baselines?projectId=${workflow.project_id}`)
      .then((rows) => {
        if (!alive) return
        const found = rows.find((b) => b.members.some((m) => m.workflowId === workflow.id))
        setRow(found)
        setDraft(found
          ? {
            name: found.name,
            deadlineTime: found.deadlineTime,
            warnBeforeMin: found.warnBeforeMin,
            level: found.level,
            owner: found.owner || '',
            enabled: found.enabled,
          }
          : { ...DEFAULT_DRAFT, name: `${workflow.name} 基线` })
        setNote('')
      })
      .catch((e) => {
        if (alive) setNote(e instanceof Error ? e.message : '加载基线失败')
      })
      .finally(() => {
        if (alive) setLoading(false)
      })
    return () => { alive = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const reload = async () => {
    const rows = await api<BaselineRow[]>(`/api/baselines?projectId=${workflow.project_id}`)
    const found = rows.find((b) => b.members.some((m) => m.workflowId === workflow.id))
    setRow(found)
    setDraft(found
      ? {
        name: found.name,
        deadlineTime: found.deadlineTime,
        warnBeforeMin: found.warnBeforeMin,
        level: found.level,
        owner: found.owner || '',
        enabled: found.enabled,
      }
      : { ...DEFAULT_DRAFT, name: `${workflow.name} 基线` })
  }

  const save = async () => {
    if (saving) return
    if (draft.enabled && !draft.name.trim()) {
      message.warning('请填写基线名称')
      return
    }
    setSaving(true)
    try {
      const body = {
        projectId: workflow.project_id,
        name: draft.name.trim(),
        deadlineTime: draft.deadlineTime,
        warnBeforeMin: draft.warnBeforeMin,
        level: draft.level,
        owner: draft.owner.trim() || undefined,
        enabled: draft.enabled,
        // 已有基线保留全部成员；没有则只挂当前任务
        workflowIds: row ? row.members.map((m) => m.workflowId) : [workflow.id],
      }
      if (row) {
        await put(`/api/baselines/${row.id}`, body)
        message.success('基线已更新')
      } else {
        await post('/api/baselines', body)
        message.success('基线已创建')
      }
      await reload()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存基线失败')
    } finally {
      setSaving(false)
    }
  }

  const remove = async () => {
    if (!row) return
    try {
      await del(`/api/baselines/${row.id}`)
      message.success('基线已删除')
      await reload()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '删除基线失败')
    }
  }

  const shared = (row?.members.length ?? 0) > 1
  // 没挂基线且开关没打开时没有可保存的内容
  const showActions = writable && (Boolean(row) || draft.enabled)
  return (
    <>
      <Divider style={{ margin: '12px 0' }} />
      <Space direction="vertical" size={8} style={{ width: '100%' }}>
        <Space style={{ width: '100%', justifyContent: 'space-between' }}>
          <Typography.Text strong>任务基线</Typography.Text>
          <Switch
            checkedChildren="启用"
            unCheckedChildren="停用"
            checked={draft.enabled}
            disabled={!writable || loading}
            onChange={(checked) => setDraft((d) => ({ ...d, enabled: checked }))}
          />
        </Space>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          承诺完成时间与提前预警在下方设置；停用后基线菜单仍能看到「已停用」。
        </Typography.Text>
        {note ? (
          <Typography.Text type="danger" style={{ fontSize: 12 }}>{note}</Typography.Text>
        ) : null}
        {draft.enabled ? (
          <Space direction="vertical" size={8} style={{ width: '100%' }}>
            <div>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>基线名称</Typography.Text>
              <Input
                value={draft.name}
                maxLength={128}
                disabled={!writable}
                placeholder="例如：核心报表 07:00"
                onChange={(e) => setDraft((d) => ({ ...d, name: e.target.value }))}
              />
            </div>
            <Space size={8} align="start" style={{ width: '100%', display: 'flex' }}>
              <div style={{ flex: 1 }}>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>承诺完成时间</Typography.Text>
                <TimePicker
                  format="HH:mm"
                  minuteStep={5}
                  style={{ width: '100%' }}
                  disabled={!writable}
                  value={dayjs(`2000-01-01T${draft.deadlineTime}:00`)}
                  onChange={(value) => {
                    if (value) setDraft((d) => ({ ...d, deadlineTime: value.format('HH:mm') }))
                  }}
                />
              </div>
              <div style={{ width: 100 }}>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>预警(分)</Typography.Text>
                <InputNumber
                  min={0}
                  max={1440}
                  style={{ width: '100%' }}
                  disabled={!writable}
                  value={draft.warnBeforeMin}
                  onChange={(value) => setDraft((d) => ({
                    ...d,
                    warnBeforeMin: typeof value === 'number' ? value : 30,
                  }))}
                />
              </div>
              <div style={{ width: 80 }}>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>级别</Typography.Text>
                <Select
                  style={{ width: '100%' }}
                  disabled={!writable}
                  value={draft.level}
                  options={[{ value: 'P1' }, { value: 'P2' }, { value: 'P3' }]}
                  onChange={(value) => setDraft((d) => ({ ...d, level: value }))}
                />
              </div>
            </Space>
            <div>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>负责人</Typography.Text>
              <Input
                value={draft.owner}
                maxLength={64}
                disabled={!writable}
                placeholder="选填，默认不指定"
                onChange={(e) => setDraft((d) => ({ ...d, owner: e.target.value }))}
              />
            </div>
            <Typography.Text type="secondary" style={{ fontSize: 11 }}>
              {row
                ? (shared
                  ? `该基线含 ${row.members.length} 个任务，修改会同时作用于全部成员`
                  : '该基线仅包含当前任务')
                : '保存后为当前任务新建基线'}
            </Typography.Text>
          </Space>
        ) : (
          row ? (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              当前任务挂在基线「{row.name}」（{row.enabled ? '启用中' : '已停用'}），打开开关可修改。
            </Typography.Text>
          ) : null
        )}
        {showActions ? (
          <Space size={4}>
            <Button
              size="small"
              type="primary"
              loading={saving}
              onClick={() => { save().catch(() => undefined) }}
            >
              保存基线
            </Button>
            {row && !shared ? (
              <Popconfirm
                title={`删除基线「${row.name}」？`}
                description="该基线只包含当前任务，删除后不再预警。"
                onConfirm={() => { remove().catch(() => undefined) }}
              >
                <Button size="small" danger>删除基线</Button>
              </Popconfirm>
            ) : null}
          </Space>
        ) : null}
      </Space>
    </>
  )
}
