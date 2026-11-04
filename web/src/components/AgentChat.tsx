import { useEffect, useRef, useState } from 'react'
import {
  Avatar, Button, Divider, Drawer, Empty, Input, List, Popover, Select, Space, Spin, Tag,
  Tooltip, Typography, message,
} from 'antd'
import {
  BookOutlined, CheckCircleOutlined, CloseCircleOutlined, CloseOutlined, DeleteOutlined,
  HistoryOutlined, PlusOutlined, RobotOutlined, SendOutlined, ToolOutlined,
} from '@ant-design/icons'
import {
  api, clearToken, del, getToken, post, storageGet, storageRemove, storageSet,
} from '../api'
import { can } from '../permissions'

type ToolCall = {
  id: string
  name: string
  args?: Record<string, unknown>
  ok?: boolean
  summary?: string
  durationMs?: number
  running?: boolean
}

type Msg = {
  role: 'user' | 'assistant'
  content: string
  tools: ToolCall[]
  status?: 'streaming' | 'done' | 'error'
  /** 失败的回答记住原始问题，重试时不用重新打字 */
  retry?: string
}

type ProjectOption = { id: number; name: string; code: string }

type Conversation = {
  sessionId: string
  title: string
  projectId?: number
  lastMessageAt?: string
  messageCount?: number
}

type StoredMessage = {
  seq: number
  role: 'user' | 'assistant'
  content?: string
  toolCalls?: unknown
  status?: string
}

type MemoryRow = {
  id: number
  scope: 'platform' | 'project' | 'user'
  key: string
  content: string
  kind?: 'semantic' | 'procedural' | 'profile'
  subtype?: string
  useCount?: number
  updatedAt?: string
}

// 三类记忆：procedural=制度（MUST）、semantic=事实（IS）、profile=偏好（WANT）
const MEMORY_KIND_LABEL: Record<string, { text: string; color: string }> = {
  procedural: { text: '制度', color: 'blue' },
  semantic: { text: '事实', color: 'default' },
  profile: { text: '偏好', color: 'purple' },
}

function parseTools(raw: unknown): ToolCall[] {
  let value = raw
  if (typeof value === 'string') {
    try {
      value = JSON.parse(value)
    } catch {
      return []
    }
  }
  if (!Array.isArray(value)) return []
  return value.map((item: any) => ({
    id: String(item?.id || Math.random().toString(16).slice(2)),
    name: String(item?.name || 'tool'),
    args: item?.args,
    ok: item?.ok === true,
    summary: item?.summary,
    durationMs: item?.durationMs,
    running: false,
  }))
}

const SUGGESTIONS = [
  '列出所有 PostgreSQL 数据源',
  '查一下最近一次同步任务的状态',
  'query_lineage 里 sync_test 相关的血缘',
  '最近的质检结果有哪些失败',
]

export default function AgentChat() {
  const [open, setOpen] = useState(false)
  const [input, setInput] = useState('')
  const [messages, setMessages] = useState<Msg[]>([])
  const [busy, setBusy] = useState(false)
  const [sessionId, setSessionId] = useState('')
  const [projects, setProjects] = useState<ProjectOption[]>([])
  // 助手查的是「哪个项目」必须由用户可见地决定，不能固定写死 projectId=1
  const [projectId, setProjectId] = useState<number>()
  // 项目列表没拿到时不能发：后端会把缺省 projectId 当成 1，答案与界面显示的范围不符
  const [projectError, setProjectError] = useState('')
  // 助手权限模式：SYSTEM=平台全权（开发测试用），DELEGATE=跟随使用者自己的权限
  const [agentCfg, setAgentCfg] = useState<{
    identityMode: 'SYSTEM' | 'DELEGATE'
    canConfigure: boolean
    allowedTools: string[]
    modes: { code: string; name: string; description: string }[]
  } | null>(null)
  // 历史对话 / 记忆：会话与服务端消息都由平台库持久化，刷新后可续聊
  const [historyOpen, setHistoryOpen] = useState(false)
  const [conversations, setConversations] = useState<Conversation[]>([])
  const [memoryOpen, setMemoryOpen] = useState(false)
  const [memories, setMemories] = useState<{
    system: MemoryRow[]; user: MemoryRow[]; project: MemoryRow[]
  }>({ system: [], user: [], project: [] })
  const [newMemoryKey, setNewMemoryKey] = useState('')
  const [newMemoryValue, setNewMemoryValue] = useState('')
  const [newMemoryScope, setNewMemoryScope] = useState<'platform' | 'project' | 'user'>('project')
  const [newMemoryKind, setNewMemoryKind] = useState<'procedural' | 'semantic' | 'profile'>('semantic')
  const [conversationSummary, setConversationSummary] = useState('')
  const listRef = useRef<HTMLDivElement>(null)
  // 组件卸载/切页时中止流，避免 patch 在卸载后继续 setState
  const abortRef = useRef<AbortController | null>(null)
  useEffect(() => () => abortRef.current?.abort(), [])

  useEffect(() => {
    if (listRef.current) {
      listRef.current.scrollTop = listRef.current.scrollHeight
    }
  }, [messages, open])

  useEffect(() => {
    if (!open) return
    api<typeof agentCfg>('/api/agent/config')
      .then(setAgentCfg)
      .catch(() => setAgentCfg(null))
  }, [open])

  useEffect(() => {
    api<ProjectOption[]>('/api/projects').then((list) => {
      setProjects(list)
      setProjectError(list.length === 0 ? '没有可用项目，无法确定查询范围' : '')
      const saved = Number(storageGet('dw_chat_project'))
      const initial = list.some((p) => p.id === saved) ? saved : list[0]?.id
      setProjectId((current) => current ?? initial)
    }).catch((e) => {
      setProjectError(e instanceof Error ? e.message : '项目列表加载失败')
    })
  }, [])

  const loadConversation = async (sid: string) => {
    try {
      const detail = await api<{
        sessionId: string; projectId?: number; summary?: string; messages?: StoredMessage[]
      }>(`/api/agent/conversations/${sid}`)
      setSessionId(detail.sessionId)
      setConversationSummary(detail.summary || '')
      storageSet('dw_chat_session', detail.sessionId)
      if (detail.projectId) setProjectId(detail.projectId)
      setMessages((detail.messages || []).map((m) => ({
        role: m.role,
        content: m.content || '',
        tools: parseTools(m.toolCalls),
        status: 'done' as const,
      })))
      setHistoryOpen(false)
    } catch {
      // 会话被删除或不属于当前用户：清掉本地指针，回到新对话
      storageRemove('dw_chat_session')
      setSessionId('')
    }
  }

  const changeMode = async (mode: 'SYSTEM' | 'DELEGATE') => {
    try {
      await api('/api/agent/config', {
        method: 'PUT',
        body: JSON.stringify({ identityMode: mode }),
      })
      setAgentCfg((prev) => (prev ? { ...prev, identityMode: mode } : prev))
      message.success(mode === 'SYSTEM' ? '已切到全权模式（测试用）' : '已切到跟随使用者权限')
    } catch (e) {
      message.error(e instanceof Error ? e.message : '切换失败')
    }
  }

  const refreshConversations = async () => {
    try {
      setConversations(await api<Conversation[]>('/api/agent/conversations'))
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载历史对话失败')
    }
  }

  const openHistory = () => {
    setHistoryOpen(true)
    void refreshConversations()
  }

  const startNewChat = () => {
    abortRef.current?.abort()
    setMessages([])
    setSessionId('')
    setInput('')
    setConversationSummary('')
    storageRemove('dw_chat_session')
    setHistoryOpen(false)
  }

  const removeConversation = async (sid: string) => {
    try {
      await del(`/api/agent/conversations/${sid}`)
      if (sid === sessionId) startNewChat()
      await refreshConversations()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '删除对话失败')
    }
  }

  const loadMemories = async () => {
    try {
      const data = await api<{
        system: MemoryRow[]; user: MemoryRow[]; project: MemoryRow[]
      }>(`/api/agent/memories${projectId ? `?projectId=${projectId}` : ''}`)
      setMemories({
        system: data.system || [], user: data.user || [], project: data.project || [],
      })
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载记忆失败')
    }
  }

  const openMemory = () => {
    setMemoryOpen(true)
    void loadMemories()
  }

  const addMemory = async () => {
    const key = newMemoryKey.trim()
    const value = newMemoryValue.trim()
    if (!key || !value) {
      message.warning('记忆名称和内容都要填')
      return
    }
    try {
      await post('/api/agent/memories', {
        scope: newMemoryScope,
        projectId: newMemoryScope === 'project' ? projectId : undefined,
        key,
        value,
        kind: newMemoryKind,
      })
      setNewMemoryKey('')
      setNewMemoryValue('')
      message.success('已保存')
      await loadMemories()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存记忆失败')
    }
  }

  const removeMemory = async (row: MemoryRow) => {
    try {
      await del(`/api/agent/memories/${row.id}`)
      await loadMemories()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '删除记忆失败')
    }
  }

  // 打开面板时恢复上次会话，刷新/重启后从平台库把消息读回来
  useEffect(() => {
    if (!open || sessionId) return
    const saved = storageGet('dw_chat_session')
    if (saved) void loadConversation(saved)
  }, [open])

  const send = async (text?: string, base?: Msg[]) => {
    const content = (text ?? input).trim()
    if (!content || busy) return
    if (!projectId) {
      setMessages((prev) => [...prev, {
        role: 'assistant',
        content: projectError || '还在加载项目列表，请稍后再试',
        tools: [],
      }])
      return
    }
    setInput('')
    setBusy(true)
    const source = base ?? messages
    const history = source.slice(-10).map((m) => ({ role: m.role, content: m.content }))
    const next: Msg[] = [...source, { role: 'user', content, tools: [] },
      { role: 'assistant', content: '', tools: [], status: 'streaming' }]
    setMessages(next)
    const idx = next.length - 1

    const patch = (fn: (m: Msg) => Msg) => {
      setMessages((prev) => prev.map((m, i) => (i === idx ? fn({ ...m, tools: [...m.tools] }) : m)))
    }

    const controller = new AbortController()
    abortRef.current?.abort()
    abortRef.current = controller
    try {
      const res = await fetch('/api/agent/chat', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${getToken() || ''}`,
        },
        body: JSON.stringify({ message: content, sessionId, history, projectId }),
        signal: controller.signal,
      })
      if (!res.ok || !res.body) {
        // 把后端的中文原因带出来，而不是只显示 HTTP 502
        let detail = ''
        try {
          const data = await res.json()
          detail = data?.message || ''
        } catch {
          // 响应不是 JSON，用状态码兜底
        }
        if (res.status === 401) {
          // 与 api() 一致：清掉失效 token 并回登录页，而不是只显示一句话
          clearToken()
          storageRemove('dw_role')
          storageRemove('dw_display')
          if (!location.pathname.startsWith('/login')) {
            const next = encodeURIComponent(location.pathname + location.search)
            location.href = `/login?redirect=${next}`
          }
          throw new Error('登录已过期，请重新登录')
        }
        throw new Error(detail || `服务暂时不可用（HTTP ${res.status}），请稍后重试`)
      }
      const reader = res.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      let assistantText = ''
      while (true) {
        const { value, done } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        const parts = buffer.split('\n\n')
        buffer = parts.pop() || ''
        for (const part of parts) {
          const line = part.split('\n').find((l) => l.startsWith('data:'))
          if (!line) continue
          let evt: any
          try {
            evt = JSON.parse(line.slice(5).trim())
          } catch {
            continue
          }
          if (evt.type === 'start' && evt.sessionId) {
            setSessionId(evt.sessionId)
            storageSet('dw_chat_session', evt.sessionId)
          } else if (evt.type === 'delta') {
            assistantText += evt.text || ''
            patch((m) => ({ ...m, content: assistantText }))
          } else if (evt.type === 'tool_call') {
            patch((m) => ({
              ...m,
              tools: [...m.tools, {
                id: evt.id, name: evt.name, args: evt.args, running: true,
              }],
            }))
          } else if (evt.type === 'tool_result') {
            patch((m) => ({
              ...m,
              tools: m.tools.map((t) => t.id === evt.id
                ? { ...t, running: false, ok: evt.ok, summary: evt.summary, durationMs: evt.durationMs }
                : t),
            }))
          } else if (evt.type === 'final') {
            assistantText = evt.text || assistantText
            patch((m) => ({ ...m, content: assistantText, status: 'done' }))
          } else if (evt.type === 'error') {
            patch((m) => ({ ...m, content: `调用失败：${evt.message}`, status: 'error', retry: content }))
          }
        }
      }
      patch((m) => (m.status === 'streaming' ? { ...m, status: 'done' } : m))
    } catch (e: any) {
      if (e?.name === 'AbortError') return
      const raw = e?.message || String(e)
      const reason = /failed to fetch|networkerror|load failed/i.test(raw)
        ? '无法连接服务器，请检查网络或稍后重试'
        : raw
      patch((m) => ({ ...m, content: `调用失败：${reason}`, status: 'error', retry: content }))
    } finally {
      setBusy(false)
    }
  }

  /** 重试失败的问题：先把这条问答从列表里摘掉，避免出现两组重复气泡。 */
  const retryAt = (index: number, text: string) => {
    if (busy) return
    const base = messages.slice(0, Math.max(0, index - 1))
    setMessages(base)
    void send(text, base)
  }

  return (
    <>
      {!open && (
        <Button
          type="primary"
          shape="round"
          icon={<RobotOutlined />}
          onClick={() => setOpen(true)}
          style={{
            position: 'fixed', right: 28, bottom: 28, height: 46, paddingInline: 20,
            boxShadow: '0 8px 24px rgba(22,119,255,.35)', zIndex: 1000,
          }}
        >
          数据中台助手
        </Button>
      )}
      {open && (
        <div style={{
          position: 'fixed', right: 24, bottom: 24,
          width: 'min(440px, calc(100vw - 32px))', height: 'min(620px, calc(100vh - 100px))',
          background: '#fff', borderRadius: 14, display: 'flex', flexDirection: 'column',
          boxShadow: '0 12px 40px rgba(0,0,0,.18)', zIndex: 1000, overflow: 'hidden',
          border: '1px solid #e6eaf1',
        }}>
          <div style={{
            padding: '12px 16px', background: 'linear-gradient(90deg,#1677ff,#4096ff)',
            color: '#fff', display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          }}>
            <Space>
              <RobotOutlined />
              <span style={{ fontWeight: 600, whiteSpace: 'nowrap' }}>数据中台助手</span>
              <Tag color="blue" style={{ marginLeft: 4 }}>查询只读 · 写入需确认</Tag>
            </Space>
            <Space size={2}>
              <Tooltip title="历史对话">
                <Button type="text" size="small" title="历史对话" aria-label="历史对话"
                  icon={<HistoryOutlined style={{ color: '#fff' }} />}
                  onClick={openHistory} />
              </Tooltip>
              <Tooltip title="新对话">
                <Button type="text" size="small" title="新对话" aria-label="新对话"
                  icon={<PlusOutlined style={{ color: '#fff' }} />}
                  onClick={startNewChat} />
              </Tooltip>
              <Tooltip title="记忆（记住/忘记）">
                <Button type="text" size="small" title="记忆" aria-label="记忆"
                  icon={<BookOutlined style={{ color: '#fff' }} />}
                  onClick={openMemory} />
              </Tooltip>
              <Button type="text" size="small" icon={<CloseOutlined style={{ color: '#fff' }} />}
                onClick={() => setOpen(false)} />
            </Space>
          </div>

          <div style={{
            padding: '6px 14px', borderBottom: '1px solid #eef1f5', background: '#fff',
            display: 'flex', alignItems: 'center', gap: 8,
          }}>
              {agentCfg && (
                <Popover
                  placement="bottom"
                  title="助手权限模式"
                  content={(
                    <div style={{ width: 288 }}>
                      {(agentCfg.modes || []).map((m) => (
                        <div key={m.code} style={{ marginBottom: 10 }}>
                          <Button
                            size="small"
                            type={agentCfg.identityMode === m.code ? 'primary' : 'default'}
                            disabled={!agentCfg.canConfigure || agentCfg.identityMode === m.code}
                            onClick={() => changeMode(m.code as 'SYSTEM' | 'DELEGATE')}
                          >
                            {m.name}
                          </Button>
                          <div style={{ color: '#8c8c8c', fontSize: 12, marginTop: 2 }}>
                            {m.description}
                          </div>
                        </div>
                      ))}
                      {!agentCfg.canConfigure && (
                        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                          需要「配置助手权限模式」才能切换，当前角色未授权。
                        </Typography.Text>
                      )}
                    </div>
                  )}
                >
                  <Tag
                    color={agentCfg.identityMode === 'SYSTEM' ? 'orange' : 'green'}
                    style={{ cursor: 'pointer' }}
                  >
                    {agentCfg.identityMode === 'SYSTEM' ? '全权（测试）' : '按我的权限'}
                  </Tag>
                </Popover>
              )}
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>查询范围</Typography.Text>
            <Select
              size="small"
              style={{ flex: 1 }}
              value={projectId}
              placeholder="选择项目"
              onChange={(value) => {
                setProjectId(value)
                storageSet('dw_chat_project', String(value))
                // 换项目等于换上下文：开一段新对话，避免记忆/历史串项目
                if (sessionId || messages.length) startNewChat()
              }}
              options={projects.map((p) => ({ value: p.id, label: `${p.name} (${p.code})` }))}
            />
          </div>
          {projectError ? (
            <div style={{ padding: '4px 14px', fontSize: 12, color: '#cf1322', background: '#fff' }}>
              {projectError}
            </div>
          ) : null}

          <div ref={listRef} style={{ flex: 1, overflow: 'auto', padding: 14, background: '#f7f9fc' }}>
            {conversationSummary ? (
              <div style={{
                marginBottom: 12, padding: '8px 10px', borderRadius: 8,
                background: '#f0f5ff', border: '1px solid #adc6ff',
                color: '#1d39c4', fontSize: 12, whiteSpace: 'pre-wrap',
              }}>
                <Typography.Text strong style={{ fontSize: 12 }}>更早对话摘要</Typography.Text>
                <div>{conversationSummary}</div>
              </div>
            ) : null}
            {messages.length === 0 && (
              <div style={{ paddingTop: 24 }}>
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description="用自然语言查询数据源、同步任务、血缘和质量结果" />
                <Space direction="vertical" style={{ width: '100%', marginTop: 12 }}>
                  {SUGGESTIONS.map((s) => (
                    <Button key={s} block onClick={() => send(s)}>{s}</Button>
                  ))}
                </Space>
              </div>
            )}
            {messages.map((m, i) => (
              <div key={i} data-role={m.role} style={{
                display: 'flex', justifyContent: m.role === 'user' ? 'flex-end' : 'flex-start',
                marginBottom: 12,
              }}>
                {m.role === 'assistant' && (
                  <Avatar size={28} icon={<RobotOutlined />}
                    style={{ marginRight: 8, background: '#1677ff', flexShrink: 0 }} />
                )}
                <div style={{ maxWidth: '82%' }}>
                  {m.tools.map((t) => (
                    <div key={t.id} style={{
                      background: '#fff', border: '1px solid #e6eaf1', borderRadius: 8,
                      padding: '6px 10px', marginBottom: 6, fontSize: 12,
                    }}>
                      <Space size={6}>
                        <ToolOutlined style={{ color: '#1677ff' }} />
                        <Typography.Text strong style={{ fontSize: 12 }}>{t.name}</Typography.Text>
                        {t.running
                          ? <Spin size="small" />
                          : t.ok ? <CheckCircleOutlined style={{ color: '#52c41a' }} />
                            : <CloseCircleOutlined style={{ color: '#ff4d4f' }} />}
                        {t.durationMs != null && (
                          <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                            {t.durationMs}ms
                          </Typography.Text>
                        )}
                      </Space>
                      <div style={{ color: '#8c8c8c', marginTop: 2, wordBreak: 'break-all' }}>
                        {t.summary || JSON.stringify(t.args || {})}
                      </div>
                    </div>
                  ))}
                  {(m.content || m.status === 'streaming') && (
                    <div style={{
                      background: m.role === 'user' ? '#1677ff' : '#fff',
                      color: m.role === 'user' ? '#fff' : '#1f2329',
                      borderRadius: 10, padding: '8px 12px', whiteSpace: 'pre-wrap',
                      border: m.role === 'user' ? 'none' : '1px solid #e6eaf1',
                      lineHeight: 1.6, fontSize: 13,
                    }}>
                      {m.content || <Spin size="small" />}
                    </div>
                  )}
                  {m.status === 'error' && m.retry && (
                    <Button
                      size="small"
                      style={{ marginTop: 6 }}
                      disabled={busy}
                      onClick={() => retryAt(i, m.retry as string)}
                    >
                      重试
                    </Button>
                  )}
                </div>
              </div>
            ))}
          </div>

          <div style={{ padding: 10, borderTop: '1px solid #eef1f5', background: '#fff' }}>
            <Space.Compact style={{ width: '100%' }}>
              <Input
                value={input}
                onChange={(e) => setInput(e.target.value)}
                onPressEnter={() => send()}
                placeholder="例如：列出所有 PostgreSQL 数据源并查最近一次同步状态"
                disabled={busy}
              />
              <Button type="primary" icon={<SendOutlined />} loading={busy}
                onClick={() => send()} />
            </Space.Compact>
            <div style={{ color: '#98a2b3', fontSize: 11, marginTop: 6 }}>
              查询直接执行 · 建/跑/停需确认 · 删除与改配置只出申请 · 会话与记忆已持久化
            </div>
          </div>
        </div>
      )}

      <Drawer title="历史对话" width={380} open={historyOpen}
        onClose={() => setHistoryOpen(false)}
        extra={<Button size="small" icon={<PlusOutlined />} onClick={startNewChat}>新对话</Button>}>
        <List<Conversation>
          dataSource={conversations}
          locale={{ emptyText: '还没有对话' }}
          renderItem={(item) => (
            <List.Item
              style={{ cursor: 'pointer' }}
              actions={[
                <Button key="del" type="text" danger size="small" icon={<DeleteOutlined />}
                  onClick={(e) => { e.stopPropagation(); void removeConversation(item.sessionId) }} />,
              ]}
              onClick={() => void loadConversation(item.sessionId)}
            >
              <List.Item.Meta
                title={item.title || '新对话'}
                description={`${item.messageCount || 0} 条消息${item.lastMessageAt
                  ? ` · ${new Date(item.lastMessageAt).toLocaleString()}` : ''}`}
              />
            </List.Item>
          )}
        />
      </Drawer>

      <Drawer title="记忆" width={420} open={memoryOpen}
        onClose={() => setMemoryOpen(false)}>
        <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
          三类记忆：制度（platform 由管理员维护，全平台生效；project 由负责人维护）、
          事实（项目/个人）、偏好（只对自己生效、只影响表达）。也可以直接对助手说
          「记住 项目库是 gongxiang_dev」。
        </Typography.Paragraph>
        {([
          ['平台制度', memories.system],
          ['项目记忆', memories.project],
          ['用户记忆', memories.user],
        ] as const).map(([title, rows]) => (
          <div key={title} style={{ marginBottom: 16 }}>
            <Typography.Text strong>{title}</Typography.Text>
            {rows.length === 0 ? (
              <div style={{ color: '#98a2b3', fontSize: 12, marginTop: 6 }}>暂无</div>
            ) : rows.map((row) => (
              <div key={row.id} data-memory-key={row.key} data-memory-scope={row.scope} style={{
                display: 'flex', gap: 8, alignItems: 'flex-start', marginTop: 8,
                padding: '8px 10px', border: '1px solid #eef1f5', borderRadius: 8,
              }}>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <Tag color={row.scope === 'platform' ? 'red' : 'blue'}>{row.key}</Tag>
                  <Tag color={MEMORY_KIND_LABEL[row.kind || 'semantic']?.color || 'default'}>
                    {MEMORY_KIND_LABEL[row.kind || 'semantic']?.text || '事实'}
                  </Tag>
                  <span style={{ fontSize: 13, wordBreak: 'break-all' }}>{row.content || ''}</span>
                </div>
                {row.scope !== 'platform' || can('user:manage') ? (
                  <Button type="text" danger size="small" icon={<DeleteOutlined />}
                    onClick={() => void removeMemory(row)} />
                ) : null}
              </div>
            ))}
          </div>
        ))}
        <Divider style={{ margin: '12px 0' }}>手动新增</Divider>
        <Space direction="vertical" size={8} style={{ width: '100%' }}>
          <Space size={8}>
            <Select
              size="small"
              value={newMemoryScope}
              onChange={setNewMemoryScope}
              style={{ width: 130 }}
              options={[
                ...(can('user:manage')
                  ? [{ value: 'platform', label: '平台制度（全局）' }]
                  : []),
                { value: 'project', label: '项目级' },
                { value: 'user', label: '用户级' },
              ]}
            />
            <Select
              size="small"
              value={newMemoryKind}
              onChange={setNewMemoryKind}
              style={{ width: 110 }}
              options={[
                { value: 'semantic', label: '事实' },
                { value: 'procedural', label: '制度' },
                { value: 'profile', label: '偏好' },
              ]}
            />
          </Space>
          <Input size="small" placeholder="名称，例如 项目库" value={newMemoryKey}
            onChange={(e) => setNewMemoryKey(e.target.value)} />
          <Input.TextArea rows={2} placeholder="内容，例如 gongxiang_dev"
            value={newMemoryValue} onChange={(e) => setNewMemoryValue(e.target.value)} />
          <Button size="small" type="primary" onClick={() => void addMemory()}>保存记忆</Button>
        </Space>
      </Drawer>
    </>
  )
}
