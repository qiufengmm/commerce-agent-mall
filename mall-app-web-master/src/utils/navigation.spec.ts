import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { goBackOrHome } from './navigation'

describe('goBackOrHome', () => {
  const navigateBack = vi.fn()
  const switchTab = vi.fn()

  beforeEach(() => {
    navigateBack.mockReset()
    switchTab.mockReset()
    vi.stubGlobal('uni', { navigateBack, switchTab })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('navigates back when the page stack has a previous page', () => {
    vi.stubGlobal('getCurrentPages', vi.fn().mockReturnValue([{}, {}]))

    goBackOrHome()

    expect(navigateBack).toHaveBeenCalledWith({ delta: 1 })
    expect(switchTab).not.toHaveBeenCalled()
  })

  it('switches to the home tab when refresh leaves only the current page', () => {
    vi.stubGlobal('getCurrentPages', vi.fn().mockReturnValue([{}]))

    goBackOrHome()

    expect(switchTab).toHaveBeenCalledWith({ url: '/pages/index/index' })
    expect(navigateBack).not.toHaveBeenCalled()
  })
})
