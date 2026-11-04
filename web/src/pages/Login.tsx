import { useEffect, useRef, useState } from 'react'
import { Alert, Button, Card, Divider, Form, Input, Spin, Typography, message } from 'antd'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { login, rememberSession, type SessionInfo } from '../auth'
import { api, post } from '../api'

function safeRedirect(value: string | null): string {
  if (!value || !value.startsWith('/') || value.startsWith('//') || value.includes('\\')) {
    return '/home'
  }
  return value
}

export default function Login() {
  const nav = useNavigate()
  const [params] = useSearchParams()
  const [loading, setLoading] = useState(false)
  const [ssoEnabled, setSsoEnabled] = useState(false)
  const [ssoSameOrigin, setSsoSameOrigin] = useState(true)
  const [ssoExchanging, setSsoExchanging] = useState(false)
  // ticket 是一次性的；StrictMode 下 effect 会跑两次，必须保证只兑换一次
  const exchangedRef = useRef(false)
  const target = safeRedirect(params.get('redirect'))

  useEffect(() => {
    api<{ enabled: boolean; sameOrigin?: boolean }>('/api/auth/sso/info')
      .then((info) => {
        setSsoEnabled(Boolean(info.enabled))
        setSsoSameOrigin(info.sameOrigin !== false)
      })
      .catch(() => setSsoEnabled(false))
  }, [])

  useEffect(() => {
    const error = params.get('sso_error')
    if (error) {
      message.error(`单点登录失败：${error}`)
      nav('/login', { replace: true })
      return
    }
    const ticket = params.get('sso_ticket')
    if (!ticket || exchangedRef.current) {
      return
    }
    exchangedRef.current = true
    setSsoExchanging(true)
    post<SessionInfo>('/api/auth/sso/session', { ticket })
      .then((result) => {
        rememberSession(result)
        nav(target, { replace: true })
      })
      .catch((e) => {
        message.error(e instanceof Error ? e.message : '单点登录失败')
        nav('/login', { replace: true })
      })
      .finally(() => setSsoExchanging(false))
  }, [params, nav, target])

  const onFinish = async (values: { username: string; password: string }) => {
    if (!values.username || !values.password) {
      message.error('请输入用户名和密码')
      return
    }
    setLoading(true)
    try {
      await login(values.username, values.password)
      nav(target, { replace: true })
    } catch (e) {
      message.error(e instanceof Error ? e.message : '登录失败')
    } finally {
      setLoading(false)
    }
  }

  const startSso = () => {
    window.location.href = `/api/auth/sso/start?redirect=${encodeURIComponent(target)}`
  }

  return (
    <div className="login-page">
      <div className="login-shell">
        <div className="login-brand-panel">
          <div className="brand-logo" style={{ width: 44, height: 44, fontSize: 18, borderRadius: 12 }}>
            AI
          </div>
          <h1>data-ai-agent 数据平台</h1>
          <p>一体化数据开发、集成、调度与治理</p>
          <div className="login-feature">
            <span>Prefect 调度与运维</span>
            <span>SeaTunnel 数据集成</span>
            <span>Gravitino 元数据与血缘</span>
            <span>Doris 查询与质量闸门</span>
          </div>
        </div>
        <Card className="login-card" bordered={false}>
          <Typography.Title level={4} style={{ marginBottom: 4 }}>
            登录控制台
          </Typography.Title>
          <Typography.Paragraph type="secondary" style={{ marginBottom: 24 }}>
            使用企业账号或本地管理员账号
          </Typography.Paragraph>
        {ssoExchanging ? (
          <div style={{ padding: '24px 0', textAlign: 'center' }}>
            <Spin />
            <Typography.Paragraph type="secondary" style={{ marginTop: 12 }}>
              正在完成单点登录…
            </Typography.Paragraph>
          </div>
        ) : (
          <>
            {/* 本地登录是主路径：SSO 依赖回调地址可达，配错时不该挡在用户前面 */}
            {/* 不预填用户名：预填 admin 会让外人误以为账号就是 admin（REQ-UX-DEFAULTS-1） */}
            <Form layout="vertical" onFinish={onFinish}>
              <Form.Item label="用户名" name="username" rules={[{ required: true }]}>
                <Input placeholder="用户名" size="large" />
              </Form.Item>
              <Form.Item label="密码" name="password" rules={[{ required: true }]}>
                <Input.Password placeholder="密码" size="large" />
              </Form.Item>
              <Button type="primary" htmlType="submit" size="large" block loading={loading}>
                登录
              </Button>
            </Form>
            {ssoEnabled && ssoSameOrigin && (
              <>
                <Divider plain style={{ color: '#999' }}>或</Divider>
                <Button size="large" block onClick={startSso}>
                  使用企业账号（Keycloak）登录
                </Button>
              </>
            )}
            {ssoEnabled && !ssoSameOrigin && (
              <Alert
                type="info"
                showIcon
                style={{ marginTop: 16 }}
                message="企业单点登录暂不可用"
                description="当前访问地址与单点登录回调地址不一致，请使用本地账号登录；如需启用请联系平台管理员。"
              />
            )}
          </>
          )}
        </Card>
      </div>
    </div>
  )
}
