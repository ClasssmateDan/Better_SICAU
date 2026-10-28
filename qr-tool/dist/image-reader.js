(function (root) {
  'use strict';

  const MAX_BYTES = 25 * 1024 * 1024;
  const MAX_PIXELS = 40 * 1000 * 1000;
  const READ_TIMEOUT = 15000;
  const SCAN_BUDGET = 10000;
  class ReadError extends Error {}
  const pause = () => new Promise((resolve) => setTimeout(resolve, 0));

  async function bitmapSource(file) {
    let expired = false;
    let timer;
    try {
      const bitmap = await Promise.race([
        root.createImageBitmap(file, { imageOrientation: 'from-image' }).then((value) => {
          if (expired) value.close();
          return value;
        }),
        new Promise((_, reject) => {
          timer = setTimeout(() => {
            expired = true;
            reject(new ReadError('图片读取超时，请尝试上传二维码区域的截图。'));
          }, READ_TIMEOUT);
        })
      ]);
      return { source: bitmap, cleanup: () => bitmap.close() };
    } finally {
      clearTimeout(timer);
    }
  }

  function imageSource(file) {
    return new Promise((resolve, reject) => {
      const url = root.URL.createObjectURL(file);
      const image = new root.Image();
      let timer;
      function release() {
        image.onload = null;
        image.onerror = null;
        clearTimeout(timer);
        root.URL.revokeObjectURL(url);
      }
      image.onload = () => {
        clearTimeout(timer);
        resolve({ source: image, cleanup: release });
      };
      image.onerror = () => {
        release();
        reject(new ReadError('无法读取这张图片，请使用 JPG、PNG 或 WebP 格式。'));
      };
      timer = setTimeout(() => {
        release();
        image.src = '';
        reject(new ReadError('图片读取超时，请尝试上传二维码区域的截图。'));
      }, READ_TIMEOUT);
      image.src = url;
    });
  }

  async function openImage(file) {
    if (typeof root.createImageBitmap === 'function') {
      try {
        return await bitmapSource(file);
      } catch (error) {
        if (error instanceof ReadError) throw error;
        // Some browsers cannot decode a particular image with createImageBitmap
        // even though their normal image loader supports it.
      }
    }
    return imageSource(file);
  }

  function makeBinary(data) {
    const histogram = new Uint32Array(256);
    const grayscale = new Uint8Array(data.length / 4);
    let sum = 0;
    for (let i = 0, pixel = 0; i < data.length; i += 4, pixel += 1) {
      const alpha = data[i + 3] / 255;
      const luminance = Math.round((data[i] * 0.299 + data[i + 1] * 0.587 + data[i + 2] * 0.114) * alpha + 255 * (1 - alpha));
      grayscale[pixel] = luminance;
      histogram[luminance] += 1;
      sum += luminance;
    }
    let belowCount = 0;
    let belowSum = 0;
    let largestVariance = -1;
    let threshold = 127;
    for (let level = 0; level < 256; level += 1) {
      belowCount += histogram[level];
      if (!belowCount) continue;
      const aboveCount = grayscale.length - belowCount;
      if (!aboveCount) break;
      belowSum += level * histogram[level];
      const difference = belowSum / belowCount - (sum - belowSum) / aboveCount;
      const variance = belowCount * aboveCount * difference * difference;
      if (variance > largestVariance) {
        largestVariance = variance;
        threshold = level;
      }
    }
    for (let i = 0, pixel = 0; i < data.length; i += 4, pixel += 1) {
      const value = grayscale[pixel] > threshold ? 255 : 0;
      data[i] = value;
      data[i + 1] = value;
      data[i + 2] = value;
      data[i + 3] = 255;
    }
  }

  async function read(file) {
    if (!file || typeof file.size !== 'number' || typeof file.arrayBuffer !== 'function') {
      throw new ReadError('请选择一张包含签到二维码的图片。');
    }
    if (!file.size) throw new ReadError('图片文件为空，请重新选择。');
    if (file.size > MAX_BYTES) throw new ReadError('图片超过 25 MB，请压缩图片或截取二维码区域后上传。');
    if (file.type && (!file.type.startsWith('image/') || /svg|heic|heif|tiff/i.test(file.type))) {
      throw new ReadError('请上传浏览器可读取的图片，例如 JPG、PNG 或 WebP。');
    }
    if (typeof root.jsQR !== 'function') throw new ReadError('二维码识别组件未加载，请刷新页面后重试。');

    let opened;
    let canvas;
    try {
      opened = await openImage(file);
      const image = opened.source;
      const width = image.naturalWidth || image.width;
      const height = image.naturalHeight || image.height;
      if (!width || !height) throw new ReadError('图片尺寸无效，请重新选择图片。');
      if (width * height > MAX_PIXELS) throw new ReadError('图片分辨率过高，请缩小图片或截取二维码区域后上传。');

      canvas = root.document.createElement('canvas');
      const context = canvas.getContext('2d', { willReadFrequently: true });
      if (!context) throw new ReadError('当前浏览器无法处理图片，请使用较新版本的浏览器。');

      // A regular photo normally succeeds on the first pass. The narrower
      // height compensates for vertically stretched classroom projectors.
      const full = [0, 0, 1, 1];
      const middle = [0.10, 0.04, 0.80, 0.76];
      const attempts = [
        { area: full, edge: 1280, vertical: 1 },
        { area: full, edge: 1280, vertical: 0.75 },
        { area: full, edge: 1800, vertical: 1 },
        { area: middle, edge: 1500, vertical: 0.75 },
        { area: full, edge: 1280, vertical: 1, binary: true, invert: true },
        { area: middle, edge: 1500, vertical: 1, binary: true, invert: true },
        { area: full, edge: 1800, vertical: 0.75, invert: true },
        { area: full, edge: 1280, vertical: 1, invert: true }
      ];
      const start = Date.now();
      for (const attempt of attempts) {
        if (Date.now() - start > SCAN_BUDGET) break;
        await pause();
        const [left, top, portionWidth, portionHeight] = attempt.area;
        const sourceWidth = width * portionWidth;
        const sourceHeight = height * portionHeight;
        const scale = Math.min(1, attempt.edge / Math.max(sourceWidth, sourceHeight * attempt.vertical));
        canvas.width = Math.max(1, Math.round(sourceWidth * scale));
        canvas.height = Math.max(1, Math.round(sourceHeight * scale * attempt.vertical));
        context.fillStyle = '#fff';
        context.fillRect(0, 0, canvas.width, canvas.height);
        context.drawImage(image, width * left, height * top, sourceWidth, sourceHeight, 0, 0, canvas.width, canvas.height);
        const pixels = context.getImageData(0, 0, canvas.width, canvas.height);
        if (attempt.binary) makeBinary(pixels.data);
        const result = root.jsQR(pixels.data, pixels.width, pixels.height, {
          inversionAttempts: attempt.invert ? 'attemptBoth' : 'dontInvert'
        });
        if (result && typeof result.data === 'string' && result.data) return result.data;
      }
      throw new ReadError('没有识别到二维码。请裁剪到二维码周围，保持四周留白，并使用清晰的原图重试。');
    } catch (error) {
      if (error && error.name === 'SecurityError') {
        throw new ReadError('浏览器无法读取这张图片，请保存为本地 JPG 或 PNG 后重新上传。');
      }
      if (error instanceof ReadError) throw error;
      throw new ReadError('图片处理失败，请换一张清晰的二维码图片重试。');
    } finally {
      if (opened) opened.cleanup();
      if (canvas) {
        canvas.width = 1;
        canvas.height = 1;
      }
    }
  }

  root.QrImage = Object.freeze({ read });
})(globalThis);

