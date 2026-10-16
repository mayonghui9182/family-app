import QRCode from 'qrcode'

function roundRect(ctx, x, y, w, h, r, fill, stroke, lineWidth = 1) {
  const radius = typeof r === 'number' ? { tl: r, tr: r, br: r, bl: r } : { tl: 0, tr: 0, br: 0, bl: 0, ...r }
  ctx.beginPath()
  ctx.moveTo(x + radius.tl, y)
  ctx.lineTo(x + w - radius.tr, y)
  ctx.quadraticCurveTo(x + w, y, x + w, y + radius.tr)
  ctx.lineTo(x + w, y + h - radius.br)
  ctx.quadraticCurveTo(x + w, y + h, x + w - radius.br, y + h)
  ctx.lineTo(x + radius.bl, y + h)
  ctx.quadraticCurveTo(x, y + h, x, y + h - radius.bl)
  ctx.lineTo(x, y + radius.tl)
  ctx.quadraticCurveTo(x, y, x + radius.tl, y)
  ctx.closePath()
  if (fill) {
    ctx.fillStyle = fill
    ctx.fill()
  }
  if (stroke) {
    ctx.strokeStyle = stroke
    ctx.lineWidth = lineWidth
    ctx.stroke()
  }
}

function textWrap(ctx, text, x, y, maxWidth, lineHeight, align = 'center') {
  ctx.textAlign = align
  ctx.textBaseline = 'top'
  const words = String(text || '').split('')
  let line = ''
  let currentY = y
  for (let i = 0; i < words.length; i++) {
    const testLine = line + words[i]
    const metrics = ctx.measureText(testLine)
    if (metrics.width > maxWidth && line !== '') {
      ctx.fillText(line, x, currentY)
      line = words[i]
      currentY += lineHeight
    } else {
      line = testLine
    }
  }
  if (line) ctx.fillText(line, x, currentY)
  return currentY
}

function fitText(ctx, text, maxWidth, startSize, minSize, fontPrefix = '') {
  let size = startSize
  while (size >= minSize) {
    ctx.font = `${fontPrefix} ${size}px "PingFang SC", "Microsoft YaHei", -apple-system, sans-serif`
    if (ctx.measureText(String(text || '')).width <= maxWidth) return size
    size -= 2
  }
  return minSize
}

export async function generateShareCard(opts) {
  if (!opts || !opts.code) throw new Error('邀请码不能为空')
  const code = String(opts.code).trim()
  const familyName = opts.familyName ? String(opts.familyName).trim() : '我的家庭'
  const userName = opts.userName ? String(opts.userName).trim() : ''

  const DPR = 2
  const W = 750, H = 1300
  const canvas = document.createElement('canvas')
  canvas.width = W * DPR
  canvas.height = H * DPR
  const ctx = canvas.getContext('2d')
  ctx.scale(DPR, DPR)

  const bgGrad = ctx.createLinearGradient(0, 0, W, 0)
  bgGrad.addColorStop(0, '#E0F2FE')
  bgGrad.addColorStop(1, '#FFFFFF')
  ctx.fillStyle = bgGrad
  ctx.fillRect(0, 0, W, H)
  roundRect(ctx, 0, 0, W, H, 24, '#FFFFFF', null)

  const topGrad = ctx.createLinearGradient(0, 0, W, 0)
  topGrad.addColorStop(0, '#2563EB')
  topGrad.addColorStop(1, '#60A5FA')
  ctx.fillStyle = topGrad
  roundRect(ctx, 0, 0, W, 136, { tl: 24, tr: 24, bl: 0, br: 0 }, topGrad, null)

  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  ctx.fillStyle = '#FFFFFF'
  ctx.font = '700 44px "PingFang SC", "Microsoft YaHei", sans-serif'
  ctx.fillText('👨‍👩‍👧‍👦 家庭助手', W / 2, 68)

  const TOP = 184

  ctx.textAlign = 'center'
  ctx.textBaseline = 'top'
  ctx.fillStyle = '#1F2937'
  ctx.font = '600 44px "PingFang SC", "Microsoft YaHei", sans-serif'
  ctx.fillText('邀请你加入我们的', W / 2, TOP)

  const familyMaxW = W - 96
  const familySize = fitText(ctx, familyName, familyMaxW, 88, 44, '800')
  ctx.font = `800 ${familySize}px "PingFang SC", "Microsoft YaHei", sans-serif`
  ctx.fillStyle = '#2563EB'
  ctx.fillText(familyName, W / 2, TOP + 72)

  let subTitleY = TOP + 196
  if (userName) {
    ctx.fillStyle = '#6B7280'
    ctx.font = '500 28px "PingFang SC", "Microsoft YaHei", sans-serif'
    ctx.fillText(`由「${userName}」邀请加入`, W / 2, subTitleY)
    subTitleY += 52
  } else {
    subTitleY = TOP + 200
  }

  ctx.fillStyle = '#6B7280'
  ctx.font = '400 28px "PingFang SC", "Microsoft YaHei", sans-serif'
  textWrap(ctx, '扫码加入，和家人一起管理待办·日常·记账·相册', W / 2, subTitleY, familyMaxW, 44)
  subTitleY += 92

  const qrBoxX = (W - 560) / 2
  const qrBoxY = subTitleY
  ctx.save()
  ctx.shadowColor = 'rgba(37, 99, 235, 0.15)'
  ctx.shadowBlur = 32
  ctx.shadowOffsetY = 8
  roundRect(ctx, qrBoxX, qrBoxY, 560, 560, 16, '#FFFFFF', '#E5E7EB', 2)
  ctx.restore()

  const qrURL = `${window.location.origin}/invite?code=${encodeURIComponent(code)}`
  const qrCanvas = document.createElement('canvas')
  await QRCode.toCanvas(qrCanvas, qrURL, {
    errorCorrectionLevel: 'M',
    margin: 1,
    width: 544,
    color: { dark: '#1E3A8A', light: '#FFFFFF' }
  })
  ctx.drawImage(qrCanvas, qrBoxX + 8, qrBoxY + 8, 544, 544)
  const qrBoxBottom = qrBoxY + 560

  ctx.fillStyle = '#4B5563'
  ctx.font = '600 32px "PingFang SC", "Microsoft YaHei", sans-serif'
  ctx.textAlign = 'center'
  ctx.textBaseline = 'top'
  ctx.fillText('邀请码', W / 2, qrBoxBottom + 40)

  const CELL_W = 100, CELL_H = 120, CELL_GAP = 12
  const totalCodeW = 6 * CELL_W + 5 * CELL_GAP
  const codeStartX = (W - totalCodeW) / 2
  const codeStartY = qrBoxBottom + 88
  for (let i = 0; i < 6; i++) {
    const ch = code[i] || ' '
    const x = codeStartX + i * (CELL_W + CELL_GAP)
    const y = codeStartY
    roundRect(ctx, x, y, CELL_W, CELL_H, 12, '#EFF6FF', '#BFDBFE', 2)
    ctx.fillStyle = '#1D4ED8'
    ctx.font = '800 96px "Courier New", monospace'
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'
    ctx.fillText(ch, x + CELL_W / 2, y + CELL_H / 2 + 2)
  }
  const codeBoxBottom = codeStartY + CELL_H

  ctx.fillStyle = '#6B7280'
  ctx.font = '400 26px "PingFang SC", "Microsoft YaHei", sans-serif'
  ctx.textAlign = 'center'
  ctx.textBaseline = 'top'
  const useY = codeBoxBottom + 56
  ctx.fillText('① 长按二维码识别，或扫码打开网页注册账号', W / 2, useY)
  ctx.fillText('② 也可在「家庭助手」里手动输入以上 6 位邀请码加入', W / 2, useY + 44)

  ctx.fillStyle = '#9CA3AF'
  ctx.font = '400 22px "PingFang SC", "Microsoft YaHei", sans-serif'
  ctx.fillText('Powered by FamilyApp · 家庭助手', W / 2, H - 44)

  return canvas.toDataURL('image/png')
}