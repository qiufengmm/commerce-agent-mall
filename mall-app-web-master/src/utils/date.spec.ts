import { describe, expect, it } from 'vitest'
import { formatDate } from './date'

describe('formatDate', () => {
  it('formats date and time tokens with leading zeroes', () => {
    const date = new Date(2026, 8, 14, 3, 4, 5)

    expect(formatDate(date, 'yyyy-MM-dd hh:mm:ss')).toBe('2026-09-14 03:04:05')
  })

  it('formats a two-digit year and single-digit tokens', () => {
    const date = new Date(2026, 8, 14, 3, 4, 5)

    expect(formatDate(date, 'yy-M-d h:m:s')).toBe('26-9-14 3:4:5')
  })
})
