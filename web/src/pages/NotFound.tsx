import { Button, Result } from 'antd'
import { useNavigate, useLocation } from 'react-router-dom'

/** 未知路由不再静默跳首页：明确告诉用户地址不对，并给一条回去的路。 */
export default function NotFound() {
  const nav = useNavigate()
  const location = useLocation()
  return (
    <Result
      status="404"
      title="页面不存在"
      subTitle={`地址 ${location.pathname} 没有对应的页面，可能是链接过期或输错了。`}
      extra={(
        <Button type="primary" onClick={() => nav('/home', { replace: true })}>
          返回首页
        </Button>
      )}
    />
  )
}
