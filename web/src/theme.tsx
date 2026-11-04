import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { ConfigProvider } from 'antd'
import zhCN from 'antd/locale/zh_CN'

export type ThemeKey = 'blue' | 'green' | 'black' | 'gray'

export const THEMES: Record<ThemeKey, {
  label: string
  primary: string
  light: string
  dark: string
}> = {
  // 主色比 antd 默认略深：白字在 #1677ff 上只有 4.10:1，达不到 WCAG AA 的 4.5:1
  blue: { label: '经典蓝', primary: '#1668dc', light: '#69b1ff', dark: '#0958d9' },
  green: { label: '经典绿', primary: '#007a52', light: '#4ccfa5', dark: '#005c3d' },
  black: { label: '经典黑', primary: '#2f3542', light: '#747d8c', dark: '#1e232b' },
  gray: { label: '灰白', primary: '#64748b', light: '#a8b3c4', dark: '#475569' },
}

type ThemeContextValue = {
  theme: ThemeKey
  setTheme: (key: ThemeKey) => void
}

const ThemeContext = createContext<ThemeContextValue>({
  theme: 'blue',
  setTheme: () => undefined,
})

export function useAppTheme(): ThemeContextValue {
  return useContext(ThemeContext)
}

function initialTheme(): ThemeKey {
  try {
    const saved = localStorage.getItem('dw_theme')
    return saved && saved in THEMES ? (saved as ThemeKey) : 'blue'
  } catch {
    return 'blue'
  }
}

export function AppThemeProvider({ children }: { children: ReactNode }) {
  const [theme, setTheme] = useState<ThemeKey>(initialTheme)
  const preset = THEMES[theme]

  useEffect(() => {
    const root = document.documentElement
    root.dataset.theme = theme
    root.style.setProperty('--dw-primary', preset.primary)
    root.style.setProperty('--dw-primary-light', preset.light)
    root.style.setProperty('--dw-primary-dark', preset.dark)
    try {
      localStorage.setItem('dw_theme', theme)
    } catch {
      // 存储禁用时主题只在本次会话生效
    }
  }, [theme, preset])

  const value = useMemo(() => ({ theme, setTheme }), [theme])

  return (
    <ThemeContext.Provider value={value}>
      <ConfigProvider
        locale={zhCN}
        button={{ autoInsertSpace: false }}
        theme={{
          token: {
            colorPrimary: preset.primary,
            colorInfo: preset.primary,
            colorLink: preset.primary,
            colorSuccess: '#00a870',
            colorWarning: '#fa8c16',
            colorError: '#f53f3f',
            colorBgLayout: '#f2f3f5',
            colorText: '#1d2129',
            colorTextSecondary: '#4e5969',
            // 12px 次级文字在浅灰底上原本只有 3.2:1，压深到 ≥4.5:1
            colorTextTertiary: '#595c63',
            colorTextPlaceholder: '#767c86',
            colorTextDescription: '#595c63',
            colorBorder: '#b9c1cc',
            colorBorderSecondary: '#dfe3e9',
            borderRadius: 6,
            fontSize: 14,
            controlHeight: 34,
            lineHeight: 1.5715,
          },
          components: {
            Layout: {
              headerBg: '#ffffff',
              bodyBg: '#f2f3f5',
              siderBg: '#001529',
              headerHeight: 56,
              headerPadding: '0 20px',
            },
            Menu: {
              darkItemBg: '#001529',
              darkSubMenuItemBg: '#000c17',
              darkItemColor: 'rgba(255,255,255,0.84)',
              darkItemHoverColor: '#ffffff',
              darkItemSelectedBg: preset.primary,
              darkItemSelectedColor: '#ffffff',
              itemHeight: 40,
              itemMarginInline: 8,
              itemBorderRadius: 6,
              iconSize: 16,
              fontSize: 14,
            },
            Card: {
              headerBg: 'transparent',
              headerFontSize: 15,
              paddingLG: 20,
            },
            Table: {
              headerBg: '#f2f3f5',
              headerColor: '#1d2129',
              cellPaddingBlockSM: 9,
              fontSize: 14,
            },
            Button: { controlHeight: 34, fontSize: 14 },
            Input: {
              activeBorderColor: preset.primary,
              hoverBorderColor: preset.primary,
              activeShadow: `0 0 0 2px ${preset.primary}26`,
              paddingInline: 11,
            },
            InputNumber: {
              activeBorderColor: preset.primary,
              hoverBorderColor: preset.primary,
            },
            Select: {
              activeBorderColor: preset.primary,
              hoverBorderColor: preset.primary,
              optionSelectedBg: `${preset.primary}14`,
            },
            DatePicker: {
              activeBorderColor: preset.primary,
              hoverBorderColor: preset.primary,
            },
            Tag: { defaultBg: '#f2f3f5' },
            Modal: { titleFontSize: 16 },
          },
        }}
      >
        {children}
      </ConfigProvider>
    </ThemeContext.Provider>
  )
}
