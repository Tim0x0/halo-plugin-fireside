/**
 * 围炉 /fireside 页面脚本：点赞、评论预览、富链接、发瞬间等交互。
 * 页面内容（含分页）全部由服务端渲染；列表页评论只做只读预览
 * （仿朋友圈灰底块，点击进详情页），完整评论组件仅详情页模板渲染。
 */
;(function () {
  'use strict';

  // 点赞走 Halo 核心计数接口；localStorage 键与主题（如 theme-earth）一致，
  // 同一浏览器在主题页和本页的点赞状态互通
  var UPVOTE_API = '/apis/api.halo.run/v1alpha1/trackers/upvote';
  var UPVOTED_STORE_KEY = 'halo.upvoted.moment.names';

  /**
   * 清理正文里的内嵌标签（a.tag，瞬间编辑器产物/友链注入）：
   * 页脚已有标签栏，正文里不重复展示。
   * 行级判断（<br> 与段落边界都算换行）：标签独占一行的删除整行
   * （含行尾 <br>，不留空行）；与文字混在一行的保留词面、去掉链接。
   * 段落被清空后整段删除。
   */
  function cleanContentTags() {
    var contents = document.querySelectorAll('.fireside-card-content');
    Array.prototype.forEach.call(contents, function (content) {
      var paragraphs = content.querySelectorAll('p');
      Array.prototype.forEach.call(paragraphs, function (p) {
        if (p.querySelector('a.tag')) {
          cleanTagLines(p);
        }
      });
      // 剩余标签（混排行/不在段落里的散落标签）退化为无链接的行内 chip：
      // 不增删任何字符（自身/友链统一），样式复用顶部标签栏的灰底圆角语言
      var rest = content.querySelectorAll('a.tag');
      Array.prototype.forEach.call(rest, function (a) {
        var chip = document.createElement('span');
        chip.className = 'fireside-inline-tag';
        chip.textContent = a.textContent;
        a.replaceWith(chip);
      });
      // 纯标签正文（如“#日常”配九图）清理后已无内容：移除容器本身，
      // 让图片网格恢复紧贴头部的负边距规则，与“本无正文”的瞬间渲染一致
      // 保留富链接占位卡（.fireside-rich-link）
      if (!content.textContent.trim()
        && !content.querySelector('img,video,audio,iframe,.fireside-rich-link')) {
        content.remove();
      }
    });
  }

  /**
   * 富链接：服务端输出占位壳，前台视口内请求官方
   * GET /apis/api.hyperlink.halo.run/v1alpha1/link-detail?url=
   * 填充标题/封面；未装超链接卡片插件或失败时保持占位。
   */
  var LINK_DETAIL_API = '/apis/api.hyperlink.halo.run/v1alpha1/link-detail';
  var richLinkCache = Object.create(null);
  var richLinkInflight = Object.create(null);
  var richLinkQueue = [];
  var richLinkActive = 0;
  var RICH_LINK_MAX_CONCURRENT = 3;

  function bindRichLinks(root) {
    var scope = root || document;
    var nodes = scope.querySelectorAll
      ? scope.querySelectorAll('[data-rich-link]:not([data-rich-done]):not([data-rich-failed])')
      : [];
    if (!nodes.length) {
      return;
    }
    // 无 IntersectionObserver 时直接排队（极老环境）
    if (typeof IntersectionObserver === 'undefined') {
      Array.prototype.forEach.call(nodes, function (el) {
        enqueueRichLink(el);
      });
      return;
    }
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (!entry.isIntersecting) {
          return;
        }
        io.unobserve(entry.target);
        enqueueRichLink(entry.target);
      });
    }, { rootMargin: '120px 0px', threshold: 0.01 });
    Array.prototype.forEach.call(nodes, function (el) {
      io.observe(el);
    });
  }

  function enqueueRichLink(el) {
    if (!el || el.getAttribute('data-rich-done') || el.getAttribute('data-rich-failed')) {
      return;
    }
    richLinkQueue.push(el);
    pumpRichLinkQueue();
  }

  function pumpRichLinkQueue() {
    while (richLinkActive < RICH_LINK_MAX_CONCURRENT && richLinkQueue.length) {
      var el = richLinkQueue.shift();
      if (!el || el.getAttribute('data-rich-done') || el.getAttribute('data-rich-failed')) {
        continue;
      }
      richLinkActive++;
      fillRichLink(el).then(function () {
        richLinkActive--;
        pumpRichLinkQueue();
      }, function () {
        richLinkActive--;
        pumpRichLinkQueue();
      });
    }
  }

  function fillRichLink(el) {
    var href = el.getAttribute('data-href') || '';
    if (!href) {
      markRichLinkFailed(el);
      return Promise.resolve();
    }
    return fetchLinkDetail(href).then(function (data) {
      if (!data) {
        markRichLinkFailed(el);
        return;
      }
      applyLinkDetail(el, data);
      el.classList.remove('is-loading');
      el.setAttribute('data-rich-done', '1');
    }, function () {
      markRichLinkFailed(el);
    });
  }

  function markRichLinkFailed(el) {
    el.classList.remove('is-loading');
    el.classList.add('is-failed');
    el.setAttribute('data-rich-failed', '1');
  }

  function fetchLinkDetail(href) {
    if (richLinkCache[href] !== undefined) {
      return Promise.resolve(richLinkCache[href]);
    }
    if (richLinkInflight[href]) {
      return richLinkInflight[href];
    }
    var qs = new URLSearchParams({ url: href });
    var p = fetch(LINK_DETAIL_API + '?' + qs.toString(), {
      credentials: 'same-origin',
      headers: { 'Accept': 'application/json' }
    }).then(function (res) {
      if (!res.ok) {
        richLinkCache[href] = null;
        return null;
      }
      return res.json().then(function (data) {
        richLinkCache[href] = data;
        return data;
      });
    }).catch(function () {
      richLinkCache[href] = null;
      return null;
    }).then(function (data) {
      delete richLinkInflight[href];
      return data;
    });
    richLinkInflight[href] = p;
    return p;
  }

  function applyLinkDetail(el, data) {
    var titleEl = el.querySelector('.fireside-rich-link__title');
    var subEl = el.querySelector('.fireside-rich-link__sub');
    var cover = el.querySelector('.fireside-rich-link__cover');
    var title = data.title && String(data.title).trim();
    var desc = data.description && String(data.description).trim();
    var image = (data.image && String(data.image).trim())
      || (data.icon && String(data.icon).trim())
      || '';
    var kind = el.getAttribute('data-kind') || '';
    if (titleEl && title) {
      titleEl.textContent = title;
    }
    // 通用网页卡副标题固定主机名（仿朋友圈来源），不被长 description 盖掉；
    // 音视频卡仍可用 description 补一句说明
    if (subEl && desc && kind !== 'link') {
      subEl.textContent = desc;
    }
    if (cover && image && /^https?:\/\//i.test(image)) {
      var defaultSrc = cover.getAttribute('src');
      cover.addEventListener('error', function onCoverErr() {
        cover.removeEventListener('error', onCoverErr);
        if (defaultSrc) {
          cover.setAttribute('src', defaultSrc);
        }
      }, { once: true });
      cover.setAttribute('src', image);
      // 外链封面带本站 origin，便于图床域名白名单；失败回退默认封面
      cover.setAttribute('referrerpolicy', 'strict-origin-when-cross-origin');
    }
  }

  /** 按 <br> 把段落切成行，删掉只含标签的行；段落清空则整段删除 */
  function cleanTagLines(p) {
    var lines = [[]];
    var nodes = Array.prototype.slice.call(p.childNodes);
    nodes.forEach(function (node) {
      // 行尾 <br> 归属本行，随行一起删除
      lines[lines.length - 1].push(node);
      if (node.nodeName === 'BR') {
        lines.push([]);
      }
    });
    lines.forEach(function (line) {
      var hasTag = line.some(function (node) {
        return node.nodeType === 1
          && (node.matches('a.tag') || node.querySelector('a.tag'));
      });
      if (hasTag && !lineTextWithoutTags(line).trim()) {
        line.forEach(function (node) { node.remove(); });
      }
    });
    if (!p.textContent.trim() && !p.querySelector('img,video,audio')) {
      p.remove();
    }
  }

  /** 该行去掉标签后剩余的文字 */
  function lineTextWithoutTags(line) {
    var text = '';
    line.forEach(function (node) {
      if (node.nodeType !== 1) {
        text += node.textContent;
        return;
      }
      if (node.matches('a.tag')) {
        return;
      }
      var clone = node.cloneNode(true);
      var tags = clone.querySelectorAll('a.tag');
      Array.prototype.forEach.call(tags, function (t) { t.remove(); });
      text += clone.textContent;
    });
    return text;
  }

  function upvotedNames() {
    try {
      return JSON.parse(localStorage.getItem(UPVOTED_STORE_KEY) || '[]');
    } catch (e) {
      return [];
    }
  }

  /** 给已点赞的按钮加高亮态 */
  function applyLikedState() {
    var names = upvotedNames();
    var buttons = document.querySelectorAll('.fireside-like');
    Array.prototype.forEach.call(buttons, function (btn) {
      if (names.indexOf(btn.dataset.name) !== -1) {
        btn.classList.add('fireside-liked');
      }
    });
  }

  function handleLike(btn) {
    var name = btn.dataset.name;
    // 已点赞或请求进行中：直接忽略，防止连点并发 POST 重复计数
    if (!name || btn.dataset.liking === '1'
      || upvotedNames().indexOf(name) !== -1
      || btn.classList.contains('fireside-liked')) {
      return;
    }
    btn.dataset.liking = '1';
    btn.disabled = true;
    fetch(UPVOTE_API, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        group: 'moment.halo.run',
        plural: 'moments',
        name: name
      })
    })
      .then(function (res) {
        if (!res.ok) throw new Error('HTTP ' + res.status);
        var names = upvotedNames();
        names.push(name);
        localStorage.setItem(UPVOTED_STORE_KEY, JSON.stringify(names));
        btn.classList.add('fireside-liked');
        var count = btn.querySelector('.fireside-like-count');
        if (count) {
          count.textContent = String(parseInt(count.textContent || '0', 10) + 1);
        }
      })
      .catch(function (err) {
        console.warn('[围炉] 点赞失败', err);
        btn.disabled = false;
      })
      .then(function () {
        delete btn.dataset.liking;
      });
  }

  /* ===== 评论预览（仿朋友圈灰底块，仅列表页模板渲染容器）=====
   * 容器由服务端出骨架（min-height + 可选「查看全部」链接），进入视口后
   * 请求 Halo 公开评论接口取「置顶 + 最新」顶层评论填充；可见性与脱敏
   * 由接口保证。评论内容可能是富文本 HTML：与官方评论组件同构，先经
   * DOMPurify 白名单消毒再插入；DOMPurify 缺席时回退纯文本兜底 */
  var COMMENTS_API = '/apis/api.halo.run/v1alpha1/comments';
  var PREVIEW_FETCH_MAX = 100; // 「全部」档（data-limit=-1）的单次抓取兜底上限

  // 白名单在官方评论组件 sanitize-html 默认档上放行 img（表情包/贴图评论）；
  // 属性只留 href/src/alt，防止评论借 class 伪装本插件 UI 元素
  var PREVIEW_SANITIZE = {
    ALLOWED_TAGS: ['a', 'p', 'br', 'b', 'strong', 'i', 'em', 'u', 's',
      'del', 'strike', 'code', 'pre', 'blockquote', 'ul', 'ol', 'li', 'span',
      'img'],
    ALLOWED_ATTR: ['href', 'src', 'alt'],
    ALLOW_DATA_ATTR: false
  };
  var purifyHooked = false;

  /** 评论富文本消毒；DOMPurify 不可用时返回 null，调用方回退纯文本 */
  function sanitizeCommentHtml(html) {
    if (typeof DOMPurify === 'undefined' || !DOMPurify.isSupported) {
      return null;
    }
    if (!purifyHooked) {
      purifyHooked = true;
      // 对齐官方组件 applyLinkAttributes：链接新标签打开并去除权重
      DOMPurify.addHook('afterSanitizeAttributes', function (node) {
        if (node.tagName === 'A') {
          node.setAttribute('target', '_blank');
          node.setAttribute('rel', 'noopener noreferrer nofollow ugc');
        }
        if (node.tagName === 'IMG') {
          node.setAttribute('loading', 'lazy');
          node.setAttribute('decoding', 'async');
          node.setAttribute('referrerpolicy', 'no-referrer');
        }
      });
    }
    return DOMPurify.sanitize(html, PREVIEW_SANITIZE);
  }

  function bindCommentPreviews() {
    var nodes = document.querySelectorAll(
      '.fireside-comment-preview[data-name]:not([data-cp-done])');
    if (!nodes.length) {
      return;
    }
    // 无 IntersectionObserver 时直接加载（极老环境）
    if (typeof IntersectionObserver === 'undefined') {
      Array.prototype.forEach.call(nodes, fillCommentPreview);
      return;
    }
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (!entry.isIntersecting) {
          return;
        }
        io.unobserve(entry.target);
        fillCommentPreview(entry.target);
      });
    }, { rootMargin: '300px 0px', threshold: 0.01 });
    Array.prototype.forEach.call(nodes, function (el) {
      io.observe(el);
    });
  }

  function fillCommentPreview(box) {
    if (box.getAttribute('data-cp-done')) {
      return;
    }
    box.setAttribute('data-cp-done', '1');
    var limit = parseInt(box.getAttribute('data-limit') || '0', 10);
    var size = limit > 0 ? Math.min(limit, PREVIEW_FETCH_MAX) : PREVIEW_FETCH_MAX;
    var qs = new URLSearchParams({
      group: 'moment.halo.run',
      version: 'v1alpha1',
      kind: 'Moment',
      name: box.getAttribute('data-name') || '',
      page: '1',
      size: String(size)
    });
    fetch(COMMENTS_API + '?' + qs.toString(), {
      credentials: 'same-origin',
      headers: { 'Accept': 'application/json' }
    })
      .then(function (res) {
        if (!res.ok) throw new Error('HTTP ' + res.status);
        return res.json();
      })
      .then(function (data) {
        renderCommentPreview(box, data);
      })
      .catch(function (err) {
        console.warn('[围炉] 评论预览加载失败', err);
        // 失败：撤掉骨架高度；没有「查看全部」链接的空盒整个移除
        box.style.minHeight = '';
        if (!box.children.length) {
          box.remove();
        }
      });
  }

  function renderCommentPreview(box, data) {
    var items = (data && data.items) || [];
    // 计数器与实际可见评论可能短暂不一致（如评论刚被隐藏），空数据不留空盒
    if (!items.length) {
      box.remove();
      return;
    }
    // 接口默认序为「置顶在前、其余最新在前」，取到的即「置顶 + 最新」；
    // 展示按时间正序重排，符合朋友圈从上往下读的时序
    items.sort(function (a, b) {
      return commentTime(a) - commentTime(b);
    });
    var more = box.querySelector('.fireside-comment-preview__more');
    var frag = document.createDocumentFragment();
    items.forEach(function (item) {
      var line = document.createElement('p');
      line.className = 'fireside-comment-preview__line';
      var owner = document.createElement('span');
      owner.className = 'fireside-comment-preview__name';
      owner.textContent = (item.owner && item.owner.displayName) || '匿名';
      line.appendChild(owner);
      line.appendChild(document.createTextNode('：'));
      var raw = (item.spec && item.spec.content) || '';
      var clean = sanitizeCommentHtml(raw);
      var text = document.createElement('span');
      text.className = 'fireside-comment-preview__text';
      if (clean === null) {
        text.textContent = raw;
      } else {
        text.innerHTML = clean;
      }
      line.appendChild(text);
      frag.appendChild(line);
    });
    box.insertBefore(frag, more || null);
    box.style.minHeight = '';
    // 「全部」档超出抓取上限、或 SSR 计数偏低时，动态补一条「查看全部」
    var total = (data && data.total) || 0;
    if (!more && total > items.length) {
      var link = document.createElement('a');
      link.className = 'fireside-comment-preview__more';
      link.href = '/fireside/'
        + encodeURIComponent(box.getAttribute('data-name') || '');
      link.textContent = '查看全部 ' + total + ' 条评论';
      box.appendChild(link);
    }
  }

  /** 评论创建时间毫秒值（排序用），解析失败按 0 沉底 */
  function commentTime(item) {
    var time = (item.spec && item.spec.creationTime)
      || (item.metadata && item.metadata.creationTimestamp) || '';
    var ms = Date.parse(time);
    return isNaN(ms) ? 0 : ms;
  }

  /** 详情页：评论区默认展开，按钮平滑滚动定位过去 */
  function scrollToComments(btn) {
    var card = btn.closest('.fireside-card');
    var box = card && card.querySelector('.fireside-comments');
    if (box) {
      box.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  }

  /** 已加载过的评论组件外链脚本：src -> { done, cbs }，跨卡片去重复用 */
  var sharedScripts = {};

  /**
   * <template> 里克隆出来的 <script> 不会执行，需原位重建节点触发执行。
   * 必须严格串行：外链库脚本等 load 完成后才执行后续内联初始化脚本
   * （动态插入的内联脚本是立即执行的，不排队，直接重建会抢跑报 undefined）。
   * 同一 src 跨卡片只加载一次，未完成时后来者挂回调排队，
   * 避免组件库二次执行（如 customElements 重复注册报错）
   */
  function reviveScriptsInOrder(scripts) {
    var queue = Array.prototype.slice.call(scripts);
    (function next() {
      var old = queue.shift();
      if (!old) {
        return;
      }
      var src = old.getAttribute('src');
      if (!src) {
        // 内联脚本：重建即同步执行，直接继续
        var inline = document.createElement('script');
        Array.prototype.forEach.call(old.attributes, function (attr) {
          inline.setAttribute(attr.name, attr.value);
        });
        inline.textContent = old.textContent;
        old.parentNode.replaceChild(inline, old);
        next();
        return;
      }
      var entry = sharedScripts[src];
      if (entry) {
        old.remove();
        if (entry.done) {
          next();
        } else {
          entry.cbs.push(next);
        }
        return;
      }
      entry = sharedScripts[src] = { done: false, cbs: [next] };
      var script = document.createElement('script');
      Array.prototype.forEach.call(old.attributes, function (attr) {
        script.setAttribute(attr.name, attr.value);
      });
      var finish = function () {
        entry.done = true;
        entry.cbs.splice(0).forEach(function (cb) { cb(); });
      };
      script.addEventListener('load', finish, { once: true });
      script.addEventListener('error', finish, { once: true });
      old.parentNode.replaceChild(script, old);
    })();
  }

  /**
   * 评论组件输出可能带固定 id（如 #comment），多卡片同页会重复冲突；
   * 挂载前改成带瞬间名的唯一 id，并同步替换内联脚本里的选择器/引用
   */
  function uniquifyIds(root, suffix) {
    var idNodes = root.querySelectorAll('[id]');
    Array.prototype.forEach.call(idNodes, function (el) {
      var oldId = el.id;
      var newId = oldId + '-' + suffix;
      el.id = newId;
      Array.prototype.forEach.call(root.querySelectorAll('script'), function (s) {
        if (!s.src && s.textContent.indexOf(oldId) !== -1) {
          s.textContent = s.textContent
            .split('#' + oldId).join('#' + newId)
            .split('"' + oldId + '"').join('"' + newId + '"')
            .split("'" + oldId + "'").join("'" + newId + "'");
        }
      });
    });
  }

  /**
   * 列表页评论按钮：原地展开/收起完整评论区（点击时才挂载官方组件）。
   * 展开时隐藏预览块避免两份评论叠放；收起时预览块重拉刷新，刚发的
   * 评论（免审核时）立刻回显。返回 false 表示环境不支持，回退原生跳转
   */
  function toggleCardComments(link) {
    var card = link.closest('.fireside-card');
    if (!card) {
      return false;
    }
    var name = link.getAttribute('data-name') || '';
    var preview = card.querySelector('.fireside-comment-preview');
    var box = card.querySelector('.fireside-comments');
    if (!box) {
      var tpl = card.querySelector('.fireside-comments-tpl');
      // 无模板或浏览器不支持 <template>（极老环境）：回退跳详情页
      if (!tpl || !tpl.content) {
        return false;
      }
      box = document.createElement('div');
      box.className = 'fireside-comments';
      box.id = 'fs-comments-' + name;
      box.appendChild(tpl.content.cloneNode(true));
      uniquifyIds(box, name);
      tpl.parentNode.insertBefore(box, tpl);
      reviveScriptsInOrder(box.querySelectorAll('script'));
    } else {
      box.hidden = !box.hidden;
    }
    var expanded = !box.hidden;
    link.setAttribute('aria-expanded', expanded ? 'true' : 'false');
    if (preview) {
      preview.hidden = expanded;
      if (!expanded) {
        refreshCommentPreview(preview);
      }
    }
    return true;
  }

  /** 清空预览行并重拉数据（收起评论区后回显最新评论） */
  function refreshCommentPreview(box) {
    if (!box || !box.isConnected) {
      return;
    }
    Array.prototype.forEach.call(
      box.querySelectorAll('.fireside-comment-preview__line'),
      function (line) { line.remove(); });
    box.removeAttribute('data-cp-done');
    fillCommentPreview(box);
  }

  /** 复制条目详情页链接，成功后按钮短暂显示"已复制" */
  function handleCopy(btn) {
    var path = btn.dataset.path;
    if (!path || btn.classList.contains('fireside-copied')) {
      return;
    }
    var url = location.origin + path;
    var done = function () {
      btn.classList.add('fireside-copied');
      setTimeout(function () {
        btn.classList.remove('fireside-copied');
      }, 1500);
    };
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(url).then(done, function () {
        fallbackCopy(url, done);
      });
    } else {
      fallbackCopy(url, done);
    }
  }

  /** clipboard API 不可用（非 https 等）时的 execCommand 兜底 */
  function fallbackCopy(text, done) {
    var input = document.createElement('textarea');
    input.value = text;
    input.style.position = 'fixed';
    input.style.opacity = '0';
    document.body.appendChild(input);
    input.select();
    try {
      document.execCommand('copy');
      done();
    } catch (e) {
      console.warn('[围炉] 复制链接失败', e);
    }
    input.remove();
  }

  document.addEventListener('click', function (e) {
    var likeBtn = e.target.closest('.fireside-like');
    if (likeBtn) {
      handleLike(likeBtn);
      return;
    }
    var copyBtn = e.target.closest('.fireside-copy');
    if (copyBtn) {
      handleCopy(copyBtn);
      return;
    }
    var scrollBtn = e.target.closest('.fireside-comment-scroll');
    if (scrollBtn) {
      scrollToComments(scrollBtn);
      return;
    }
    var commentLink = e.target.closest('.fireside-comment-link');
    if (commentLink) {
      // 修饰键/中键点击保留原生行为（新标签打开详情页）
      if (e.ctrlKey || e.metaKey || e.shiftKey || e.altKey || e.button !== 0) {
        return;
      }
      if (toggleCardComments(commentLink)) {
        e.preventDefault();
      }
      return;
    }
    var preview = e.target.closest('.fireside-comment-preview');
    if (preview) {
      // 点中内部真实链接（「查看全部」）走原生跳转，保留新标签等修饰键行为
      if (e.target.closest('a')) {
        return;
      }
      // 评论里的图片（表情包等）留给主题灯箱接管，不触发整块跳详情
      if (e.target.closest('img')) {
        return;
      }
      // 用户正选中评论文字复制时不触发跳转
      var selection = window.getSelection();
      if (selection && String(selection)) {
        return;
      }
      var momentName = preview.getAttribute('data-name');
      if (momentName) {
        location.href = '/fireside/' + encodeURIComponent(momentName);
      }
    }
  });

  /**
   * 媒体图片加载三态：默认加载中（CSS 灰底）→ is-loaded 去底 → is-failed「加载失败」。
   * 缓存命中时 img.complete 已为 true，需同步判定，不能只靠 load 事件。
   */
  function bindMediaLoading() {
    var imgs = document.querySelectorAll('.fireside-media-item > img');
    Array.prototype.forEach.call(imgs, function (img) {
      var item = img.parentElement;
      if (!item) {
        return;
      }
      var markLoaded = function () {
        item.classList.remove('is-failed');
        item.classList.add('is-loaded');
      };
      var markFailed = function () {
        item.classList.remove('is-loaded');
        item.classList.add('is-failed');
        // 裂图仍留在 DOM（供重试扩展），对读屏改成失败语义
        if (!img.getAttribute('alt') || img.getAttribute('alt') === '图片') {
          img.setAttribute('alt', '加载失败');
        }
      };
      if (img.complete) {
        if (img.naturalWidth > 0) {
          markLoaded();
        } else {
          // complete 且无固有尺寸：坏链、空 src，或仍在重试前的失败态
          markFailed();
        }
        return;
      }
      img.addEventListener('load', markLoaded, { once: true });
      img.addEventListener('error', markFailed, { once: true });
    });
  }

  applyLikedState();
  cleanContentTags();
  bindMediaLoading();
  bindRichLinks(document);
  bindCommentPreviews();

  // 标签栏：桌面端滚轮转为横向滚动（滚动条已隐藏，移动端触摸原生支持）
  var tagsNav = document.querySelector('.fireside-tags');
  if (tagsNav) {
    tagsNav.addEventListener('wheel', function (e) {
      if (tagsNav.scrollWidth <= tagsNav.clientWidth) {
        return;
      }
      if (Math.abs(e.deltaY) > Math.abs(e.deltaX)) {
        tagsNav.scrollLeft += e.deltaY;
        e.preventDefault();
      }
    }, { passive: false });
  }

  // 返回顶部：滚过一屏后出现（折叠类走 CSS 高度/透明度缓动，比 hidden 柔和）
  var topBtn = document.getElementById('fs-top');
  if (topBtn) {
    var syncTopBtn = function () {
      topBtn.classList.toggle('fireside-fab-collapsed',
        window.scrollY < window.innerHeight);
    };
    window.addEventListener('scroll', syncTopBtn, { passive: true });
    syncTopBtn();
    topBtn.addEventListener('click', function () {
      window.scrollTo({ top: 0, behavior: 'smooth' });
    });
  }

  // 配色切换：三态循环（自动→浅→深），选择存 localStorage。
  // 首帧应用在模板 <head> 内联脚本完成，这里只负责循环与图标/提示同步
  var schemeBtn = document.getElementById('fs-scheme');
  if (schemeBtn) {
    var SCHEME_KEY = 'fireside.scheme';
    var SCHEME_ORDER = ['auto', 'light', 'dark'];
    var SCHEME_TITLE = {
      auto: '配色：跟随系统',
      light: '配色：浅色',
      dark: '配色：深色'
    };
    var currentScheme = function () {
      var value = document.documentElement.getAttribute('data-fs-scheme');
      return SCHEME_ORDER.indexOf(value) !== -1 ? value : 'auto';
    };
    var syncSchemeTitle = function () {
      schemeBtn.title = SCHEME_TITLE[currentScheme()];
    };
    schemeBtn.addEventListener('click', function () {
      var next = SCHEME_ORDER[
        (SCHEME_ORDER.indexOf(currentScheme()) + 1) % SCHEME_ORDER.length];
      // 图标显隐由 CSS 按该属性切换，无需手动改 DOM
      document.documentElement.setAttribute('data-fs-scheme', next);
      // 同步官方评论组件的配色契约（其 var.css 按 data-color-scheme 切换）
      document.documentElement.setAttribute('data-color-scheme', next);
      try {
        localStorage.setItem(SCHEME_KEY, next);
      } catch (e) { /* 隐私模式下 localStorage 不可用，忽略 */ }
      syncSchemeTitle();
    });
    syncSchemeTitle();
  }

  /* ===== 发瞬间（入口与浮层仅登录用户的模板会渲染，访客直接跳过） ===== */
  var PERMISSIONS_API = '/apis/api.console.halo.run/v1alpha1/users/-/permissions';
  var UC_MOMENT_API = '/apis/uc.api.moment.halo.run/v1alpha1/moments';
  var UC_UPLOAD_API = '/apis/uc.api.storage.halo.run/v1alpha1/attachments/-/upload';
  var UC_TAGS_API = '/apis/uc.api.moment.halo.run/v1alpha1/tags';
  var COMPOSE_MAX_MEDIA = 9;

  var composeEntry = document.getElementById('fs-compose-entry');
  var composeBox = document.getElementById('fs-compose');
  if (composeEntry && composeBox) {
    initCompose();
  }

  function initCompose() {
    var textInput = document.getElementById('fs-compose-text');
    var tagsBox = document.getElementById('fs-compose-tags');
    var suggestBox = document.getElementById('fs-compose-suggest');
    var visibleBtn = document.getElementById('fs-compose-visible');
    var visibleValue = document.getElementById('fs-compose-visible-value');
    var fileInput = document.getElementById('fs-compose-file');
    var addBtn = document.getElementById('fs-compose-add');
    var mediaGrid = document.getElementById('fs-compose-media');
    // 发表按钮有两个实例（移动端顶栏 + 桌面端底部），状态需同步
    var submitBtns = Array.prototype.slice.call(
      composeBox.querySelectorAll('.fireside-compose-submit'));
    var tip = document.getElementById('fs-compose-tip');
    // 富文本工具栏 / 编辑器挂载点 / 链接输入行（编辑器 bundle 就绪前均隐藏）
    var toolbar = document.getElementById('fs-compose-toolbar');
    var toolbarBtns = Array.prototype.slice.call(
      toolbar.querySelectorAll('.fireside-compose-fmt'));
    var editorMount = document.getElementById('fs-compose-editor');
    var linkRow = document.getElementById('fs-compose-linkrow');
    var linkUrlInput = document.getElementById('fs-compose-link-url');
    var linkTextInput = document.getElementById('fs-compose-link-text');
    // 已选图片：{ file, url, originType, uploading, failed, objectUrl, el }
    // file 是原始 File，仿微信朋友圈到点“发表”才上传；url 上传成功后才有值
    var mediaItems = [];
    var submitting = false;
    // Tiptap 编辑器包装（fireside-editor.js 首次打开浮层时懒加载创建）；
    // null = bundle 未就绪或加载失败，走 textarea 纯文本兜底路径
    var editorWrap = null;
    var editorLoadState = ''; // '' | 'loading' | 'ready' | 'failed'
    // 链接输入行打开时是否带着非空选区（决定确定后套选区还是插入新文字）
    var linkHasSelection = false;
    // 谁可以看：false=公开(PUBLIC)，true=私密(PRIVATE，仅自己登录后可见)
    var isPrivate = false;
    // 标签识别：官方 TagsExtension 同款字符集，必须以空白终止（打完空格才生效）；
    // 额外要求 # 前是行首/空白，避免误伤 URL 锚点等混排井号
    var TAG_CHARS = '[\\u4E00-\\u9FFFa-zA-Z0-9]';
    var TAG_SCAN = new RegExp('(^|\\s)#(' + TAG_CHARS + '+)(?=\\s)', 'g');
    var TAG_AT_CARET = new RegExp('(^|\\s)#(' + TAG_CHARS + '*)$');
    // 联想状态
    var suggestItems = [];
    var suggestIndex = -1;
    var suggestFragment = null;
    var suggestTimer = null;
    var suggestSeq = 0;
    // 输入法组词过程不解析，compositionend 后统一处理
    var composing = false;

    // 入口显隐走官方 uiPermissions 契约（Console/UC 菜单同款判断）；
    // 超管的权限列表是通配符 '*'，必须一并判断
    fetch(PERMISSIONS_API)
      .then(function (res) { return res.ok ? res.json() : null; })
      .then(function (data) {
        var perms = (data && data.uiPermissions) || [];
        if (perms.indexOf('*') !== -1
          || perms.indexOf('uc:plugin:moments:publish') !== -1) {
          composeEntry.hidden = false;
        }
      })
      .catch(function () { /* 探测失败保持隐藏即可，不打扰页面 */ });

    composeEntry.addEventListener('click', openCompose);
    Array.prototype.forEach.call(
      composeBox.querySelectorAll('[data-compose-close]'),
      function (el) { el.addEventListener('click', closeCompose); });
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && !composeBox.hidden) {
        closeCompose();
      }
    });
    addBtn.addEventListener('click', function () {
      // 发表过程中（正在上传/提交）不允许再改动图片
      if (submitting) {
        return;
      }
      fileInput.click();
    });
    fileInput.addEventListener('change', function () {
      if (!submitting) {
        Array.prototype.forEach.call(fileInput.files, addMedia);
      }
      // 清空 value，同一张图删除后可再次选择
      fileInput.value = '';
    });
    visibleBtn.addEventListener('click', function () {
      isPrivate = !isPrivate;
      visibleValue.textContent = isPrivate ? '私密' : '公开';
      visibleBtn.classList.toggle('is-private', isPrivate);
    });
    submitBtns.forEach(function (btn) {
      btn.addEventListener('click', handlePublish);
    });
    toolbarBtns.forEach(function (btn) {
      // mousedown 阻止默认，保住编辑器里的选区与焦点（触摸端同样派发）
      btn.addEventListener('mousedown', function (e) {
        e.preventDefault();
      });
      btn.addEventListener('click', function () {
        if (!editorWrap || submitting) {
          return;
        }
        var fmt = btn.getAttribute('data-fmt');
        if (fmt === 'bold') {
          editorWrap.toggleBold();
        } else if (fmt === 'italic') {
          editorWrap.toggleItalic();
        } else if (fmt === 'strike') {
          editorWrap.toggleStrike();
        } else if (fmt === 'code') {
          editorWrap.toggleCode();
        } else if (fmt === 'link') {
          handleLinkButton();
        } else if (fmt === 'undo') {
          editorWrap.undo();
        } else if (fmt === 'redo') {
          editorWrap.redo();
        }
        syncToolbar();
      });
    });
    document.getElementById('fs-compose-link-ok')
      .addEventListener('click', confirmLink);
    document.getElementById('fs-compose-link-cancel')
      .addEventListener('click', closeLinkRow);
    // 链接行内 Enter 确定、Esc 取消（stopPropagation 拦住关浮层）
    linkRow.addEventListener('keydown', function (e) {
      if (e.key === 'Enter') {
        e.preventDefault();
        confirmLink();
      } else if (e.key === 'Escape') {
        e.preventDefault();
        e.stopPropagation();
        closeLinkRow();
      }
    });
    textInput.addEventListener('compositionstart', function () {
      composing = true;
    });
    textInput.addEventListener('compositionend', function () {
      composing = false;
      onTextChange();
    });
    textInput.addEventListener('input', function () {
      if (!composing) {
        onTextChange();
      }
    });
    // 光标移动（点击/方向键跳转）也会改变“光标处未完成标签”的判定
    textInput.addEventListener('click', function () {
      updateSuggest();
    });
    textInput.addEventListener('keyup', function (e) {
      if (e.key === 'ArrowLeft' || e.key === 'ArrowRight'
        || e.key === 'Home' || e.key === 'End') {
        updateSuggest();
      }
    });
    textInput.addEventListener('keydown', handleSuggestKeydown);
    // 点击输入区与联想条以外的位置时收起联想条（如去点“添加图片”）。
    // 编辑器内部点击交给 onSelectionUpdate 按光标语境判定，不在此收起；
    // 点候选项时其自身 click 先于本监听执行，联想条已收起，此处自然空跑
    document.addEventListener('click', function (e) {
      if (!suggestBox.hidden && e.target !== textInput
        && !suggestBox.contains(e.target)
        && !editorMount.contains(e.target)) {
        hideSuggest();
      }
    });
    syncTagChips();

    function openCompose() {
      composeBox.hidden = false;
      document.body.style.overflow = 'hidden';
      // 首次打开才拉编辑器 bundle；就绪前 textarea 先顶上，可立即打字
      ensureEditor();
      focusInput();
    }

    function closeCompose() {
      // 仿微信：发表进行中不允许退出，否则上传/提交与浮层状态会脱节
      // （网络异常最终会走失败分支解锁，不会困住用户）
      if (submitting) {
        showTip('正在发表，请稍候…');
        return;
      }
      composeBox.hidden = true;
      document.body.style.overflow = '';
      hideSuggest();
      closeLinkRow();
    }

    function showTip(message, ok) {
      tip.textContent = message;
      tip.hidden = false;
      tip.classList.toggle('is-ok', !!ok);
    }

    function hideTip() {
      tip.hidden = true;
      tip.classList.remove('is-ok');
    }

    /** 仿微信朋友圈：选图只做本地预览（objectURL），到点“发表”才统一上传
     * （见 uploadPendingMedia）。中途关闭浮层、移除图片都不会往附件库
     * 留下用不上的孤儿文件 */
    function addMedia(file) {
      if (file.type.indexOf('image/') !== 0) {
        return;
      }
      if (mediaItems.length >= COMPOSE_MAX_MEDIA) {
        showTip('最多添加 ' + COMPOSE_MAX_MEDIA + ' 张图片');
        return;
      }
      hideTip();
      var item = {
        file: file,
        url: '',
        originType: file.type,
        uploading: false,
        failed: false,
        objectUrl: URL.createObjectURL(file),
        el: null
      };
      item.el = buildMediaCell(item);
      mediaGrid.insertBefore(item.el, addBtn);
      mediaItems.push(item);
      syncAddButton();
    }

    function buildMediaCell(item) {
      var cell = document.createElement('div');
      cell.className = 'fireside-compose-item';
      var img = document.createElement('img');
      img.src = item.objectUrl;
      img.alt = '';
      // 禁掉浏览器原生的图片拖拽，避免抢占自定义拖拽排序的指针流
      img.draggable = false;
      var remove = document.createElement('button');
      remove.type = 'button';
      remove.className = 'fireside-compose-remove';
      remove.title = '移除';
      remove.textContent = '×';
      remove.addEventListener('click', function () {
        // 发表中不许移除（上传计数会悬空）；拖拽中的误触也一并挡掉
        if (submitting || (drag && drag.active)) {
          return;
        }
        URL.revokeObjectURL(item.objectUrl);
        cell.remove();
        var index = mediaItems.indexOf(item);
        if (index > -1) {
          mediaItems.splice(index, 1);
        }
        syncAddButton();
      });
      cell.appendChild(img);
      cell.appendChild(remove);
      cell.addEventListener('pointerdown', function (e) {
        onCellPointerDown(item, e);
      });
      // 长按拖拽期间不弹系统菜单（Android 长按、桌面右键对小预览图无意义）
      cell.addEventListener('contextmenu', function (e) {
        e.preventDefault();
      });
      return cell;
    }

    function syncAddButton() {
      addBtn.hidden = mediaItems.length >= COMPOSE_MAX_MEDIA;
    }

    /* ===== 图片拖拽排序（仿微信朋友圈）=====
     * 桌面按住即拖；移动端长按 350ms 拖起（等待期滑动视为想滚动页面）。
     * 拖起后原格子半透明占位、浮影跟手，划过其他格子时以 FLIP 动画实时
     * 让位，松手浮影飞回落点。顺序始终同步维护在 mediaItems 数组，
     * 发表时按此顺序出图。零依赖 Pointer Events 实现，与本文件风格一致 */
    var LONG_PRESS_MS = 350;   // 移动端长按触发时长
    var DRAG_START_PX = 6;     // 桌面：位移超过该距离才算拖拽（区分普通点击）
    var CANCEL_PRESS_PX = 10;  // 移动端：长按等待期滑动超过该距离则放弃拖拽
    var SWAP_LOCK_MS = 120;    // 每次让位后的命中锁，避免与飞行中的格子来回互换
    // 当前拖拽会话：{ item, cell, pointerId, startX, startY, active,
    //   ghost, rect, timer, lastSwap }，同一时刻只允许一个指针拖拽
    var drag = null;

    function onCellPointerDown(item, e) {
      if (submitting || drag) {
        return;
      }
      // 仅响应鼠标主键；触摸/笔的 button 为 0 或 -1（pointermove 时），都放行
      if (e.button > 0) {
        return;
      }
      if (e.target.closest('.fireside-compose-remove')) {
        return;
      }
      drag = {
        item: item,
        cell: item.el,
        pointerId: e.pointerId,
        startX: e.clientX,
        startY: e.clientY,
        active: false,
        ghost: null,
        rect: null,
        timer: 0,
        lastSwap: 0
      };
      if (e.pointerType !== 'mouse') {
        drag.timer = setTimeout(function () {
          startDrag(e);
        }, LONG_PRESS_MS);
      }
      document.addEventListener('pointermove', onDragPointerMove);
      document.addEventListener('pointerup', onDragPointerUp);
      document.addEventListener('pointercancel', onDragPointerUp);
    }

    function onDragPointerMove(e) {
      if (!drag || e.pointerId !== drag.pointerId) {
        return;
      }
      if (!drag.active) {
        var dx = e.clientX - drag.startX;
        var dy = e.clientY - drag.startY;
        var dist = Math.sqrt(dx * dx + dy * dy);
        if (drag.timer) {
          // 长按等待期出现明显滑动：用户是想滚动页面，放弃本次拖拽
          if (dist > CANCEL_PRESS_PX) {
            cleanupDrag(false);
          }
        } else if (dist > DRAG_START_PX) {
          startDrag(e);
        }
        if (!drag || !drag.active) {
          return;
        }
      }
      moveGhost(e);
      hitTest(e);
    }

    function startDrag(e) {
      clearTimeout(drag.timer);
      drag.timer = 0;
      drag.active = true;
      drag.rect = drag.cell.getBoundingClientRect();
      // 捕获指针：手指/鼠标移出格子甚至窗口后事件仍持续派发过来
      try {
        drag.cell.setPointerCapture(drag.pointerId);
      } catch (err) { /* 指针恰好已释放时会抛错，忽略 */ }
      drag.ghost = buildGhost(drag.cell, drag.rect);
      drag.cell.classList.add('is-dragging');
      document.body.classList.add('fireside-dragging');
      // 格子的 touch-action:pan-y 把纵向滚动留给了浏览器，
      // 拖起后必须显式接管，否则移动端拖图会连带滚屏
      document.addEventListener('touchmove', blockScrollWhileDrag, { passive: false });
      if (navigator.vibrate) {
        // 仿微信拖起瞬间的轻触感反馈（iOS 网页不支持，静默无害）
        navigator.vibrate(15);
      }
      moveGhost(e);
    }

    function blockScrollWhileDrag(e) {
      e.preventDefault();
    }

    /** 浮影 = 只含图片的克隆，fixed 定位在原格子处，之后用 transform 跟手 */
    function buildGhost(cell, rect) {
      var ghost = document.createElement('div');
      ghost.className = 'fireside-compose-ghost';
      ghost.appendChild(cell.querySelector('img').cloneNode(false));
      ghost.style.left = rect.left + 'px';
      ghost.style.top = rect.top + 'px';
      ghost.style.width = rect.width + 'px';
      ghost.style.height = rect.height + 'px';
      document.body.appendChild(ghost);
      return ghost;
    }

    function moveGhost(e) {
      drag.ghost.style.transform = 'translate(' + (e.clientX - drag.startX) + 'px,'
        + (e.clientY - drag.startY) + 'px) scale(1.05)';
    }

    function hitTest(e) {
      var now = Date.now();
      if (now - drag.lastSwap < SWAP_LOCK_MS) {
        return;
      }
      // 浮影是 pointer-events:none，这里取到的是其正下方的真实元素
      var el = document.elementFromPoint(e.clientX, e.clientY);
      var cell = el ? el.closest('.fireside-compose-item') : null;
      if (!cell || cell === drag.cell || !mediaGrid.contains(cell)) {
        return;
      }
      for (var i = 0; i < mediaItems.length; i++) {
        if (mediaItems[i].el === cell) {
          reorderTo(mediaItems[i]);
          drag.lastSwap = now;
          return;
        }
      }
    }

    /** 把拖拽中的图片挪到目标图片的位置：数组与 DOM 同步调整，
     * 其余格子用 FLIP 从旧视觉位置平滑滑到新布局位置 */
    function reorderTo(target) {
      var fromIdx = mediaItems.indexOf(drag.item);
      var toIdx = mediaItems.indexOf(target);
      if (fromIdx < 0 || toIdx < 0 || fromIdx === toIdx) {
        return;
      }
      // FLIP-first：记下各格子当前视觉位置（可能正处于上一轮让位动画途中），
      // 随后清掉旧动画变换，让 DOM 调整后能量到真实的新布局位置
      var snapshot = mediaItems.map(function (m) {
        return { el: m.el, rect: m.el.getBoundingClientRect() };
      });
      snapshot.forEach(function (s) {
        s.el.style.transition = 'none';
        s.el.style.transform = '';
      });
      mediaItems.splice(fromIdx, 1);
      mediaItems.splice(toIdx, 0, drag.item);
      mediaGrid.insertBefore(drag.item.el,
        fromIdx < toIdx ? target.el.nextSibling : target.el);
      // FLIP-invert & play：整段同步执行，中间态不会被绘制
      snapshot.forEach(function (s) {
        if (s.el === drag.cell) {
          return; // 源格子有浮影代言，占位直接跳到新位置即可
        }
        var now = s.el.getBoundingClientRect();
        var dx = s.rect.left - now.left;
        var dy = s.rect.top - now.top;
        if (!dx && !dy) {
          return;
        }
        s.el.style.transform = 'translate(' + dx + 'px,' + dy + 'px)';
        void s.el.offsetWidth; // 强制回流，让起点先生效再播放过渡
        s.el.style.transition = 'transform 0.18s ease';
        s.el.style.transform = '';
      });
    }

    function onDragPointerUp(e) {
      if (!drag || e.pointerId !== drag.pointerId) {
        return;
      }
      if (drag.active) {
        finishDrag();
      } else {
        // 长按未成或未达拖拽阈值：不拦事件，当普通点击（如点中移除按钮）
        cleanupDrag(false);
      }
    }

    function finishDrag() {
      var cell = drag.cell;
      var ghost = drag.ghost;
      var from = drag.rect;
      // 拖拽结束后浏览器会对源格子补发一次 click，拦掉避免误触格内按钮
      suppressNextClick(cell);
      var to = cell.getBoundingClientRect();
      ghost.style.transition = 'transform 0.16s ease';
      ghost.style.transform = 'translate(' + (to.left - from.left) + 'px,'
        + (to.top - from.top) + 'px) scale(1)';
      setTimeout(function () {
        ghost.remove();
        cell.classList.remove('is-dragging');
      }, 180);
      cleanupDrag(true);
    }

    /** 收尾；keepVisual=true 时浮影与占位态交给 finishDrag 的归位动画清理 */
    function cleanupDrag(keepVisual) {
      if (!drag) {
        return;
      }
      clearTimeout(drag.timer);
      if (!keepVisual) {
        if (drag.ghost) {
          drag.ghost.remove();
        }
        drag.cell.classList.remove('is-dragging');
      }
      document.body.classList.remove('fireside-dragging');
      document.removeEventListener('pointermove', onDragPointerMove);
      document.removeEventListener('pointerup', onDragPointerUp);
      document.removeEventListener('pointercancel', onDragPointerUp);
      document.removeEventListener('touchmove', blockScrollWhileDrag);
      drag = null;
    }

    /** 以捕获方式吞掉紧随 pointerup 的那次 click。click 与 pointerup 在
     * 同一任务内先后派发，之后的宏任务里移除即可；若本次没有补发 click
     * （如 pointercancel 收尾），监听也会被移除，不误伤下一次真点击 */
    function suppressNextClick(el) {
      function block(ev) {
        ev.preventDefault();
        ev.stopPropagation();
      }
      el.addEventListener('click', block, true);
      setTimeout(function () {
        el.removeEventListener('click', block, true);
      }, 0);
    }

    /* ===== 桌面拖文件入卡片上传（移动端无文件拖拽事件，天然不触发）=====
     * 整个发表卡片都是接收区，拖悬时高亮提示，松手走 addMedia 复用
     * 类型过滤与 9 张上限；发表锁定期拖入静默忽略 */
    var panel = composeBox.querySelector('.fireside-compose-panel');
    var dropDepth = 0;
    // 浮层整体（含遮罩）兜底拦掉浏览器默认行为：拖到遮罩上松手时，
    // 不至于让浏览器直接跳去打开图片文件。dragover 在此层 preventDefault
    // 对冒泡上来的卡片区事件同样生效，故卡片无需重复监听 dragover
    composeBox.addEventListener('dragover', function (e) {
      if (dragHasFiles(e)) {
        e.preventDefault();
      }
    });
    composeBox.addEventListener('drop', function (e) {
      if (dragHasFiles(e)) {
        e.preventDefault();
      }
    });
    panel.addEventListener('dragenter', function (e) {
      if (!dragHasFiles(e) || submitting) {
        return;
      }
      e.preventDefault();
      dropDepth++;
      panel.classList.add('is-dropping');
    });
    panel.addEventListener('dragleave', function (e) {
      if (!dragHasFiles(e)) {
        return;
      }
      // 拖着文件在卡片子元素间穿梭会成对触发 enter/leave，计数归零才算真离开
      dropDepth--;
      if (dropDepth <= 0) {
        dropDepth = 0;
        panel.classList.remove('is-dropping');
      }
    });
    panel.addEventListener('drop', function (e) {
      if (!dragHasFiles(e)) {
        return;
      }
      e.preventDefault();
      dropDepth = 0;
      panel.classList.remove('is-dropping');
      if (!submitting) {
        Array.prototype.forEach.call(e.dataTransfer.files, addMedia);
      }
    });

    /** 只认真正的文件拖拽：拖选中文字/页面图片时 types 里没有 'Files'，
     * 不高亮也不拦默认行为 */
    function dragHasFiles(e) {
      var types = e.dataTransfer && e.dataTransfer.types;
      if (!types) {
        return false;
      }
      for (var i = 0; i < types.length; i++) {
        if (types[i] === 'Files') {
          return true;
        }
      }
      return false;
    }

    /** 纯文本转瞬间正文 HTML：先转义，再把已生效标签替换为官方格式的
     * a.tag 节点（与瞬间插件 TagsExtension.renderHTML 产物一致：
     * class/href/data-pjax，井号按官方行为去掉、节点文本为纯标签名），
     * 最后双换行分段、单换行转 <br>。 */
    function textToHtml(text, tags) {
      if (!text) {
        return '';
      }
      var tagSet = {};
      tags.forEach(function (tag) { tagSet[tag] = true; });
      var escaped = text
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
      // 终止符放宽到“空白或结尾”：首尾清理可能吃掉用户敲的终止空格，
      // tagSet 门禁保证只包装“确实敲过空格”的标签
      var wrapPattern = new RegExp('(^|\\s)#(' + TAG_CHARS + '+)(?=\\s|$)', 'g');
      var withTags = escaped.replace(wrapPattern, function (all, boundary, name) {
        if (!tagSet[name]) {
          return all;
        }
        return boundary + '<a class="tag" href="/moments?tag='
          + encodeURIComponent(name) + '" data-pjax="">' + name + '</a>';
      });
      return withTags.split(/\n{2,}/).map(function (block) {
        return '<p>' + block.replace(/\n/g, '<br>') + '</p>';
      }).join('');
    }

    /** 正文里已生效的标签（# + 官方字符集 + 已敲空白终止），按出现顺序去重 */
    function parseContentTags(text) {
      var seen = {};
      var tags = [];
      var match;
      TAG_SCAN.lastIndex = 0;
      while ((match = TAG_SCAN.exec(text)) !== null) {
        var tag = match[2];
        if (!seen[tag]) {
          seen[tag] = true;
          tags.push(tag);
        }
      }
      return tags;
    }

    function onTextChange() {
      syncTagChips();
      updateSuggest();
    }

    /** 标签行 = 正文的实时投影：只展示与删除，不支持行内新增 */
    function syncTagChips() {
      var tags = parseContentTags(sourceText());
      tagsBox.textContent = '';
      if (!tags.length) {
        var empty = document.createElement('span');
        empty.className = 'fireside-compose-tags-empty';
        empty.textContent = '在正文中输入 #标签 添加';
        tagsBox.appendChild(empty);
        return;
      }
      tags.forEach(function (tag) {
        var chip = document.createElement('span');
        chip.className = 'fireside-compose-tag-chip';
        chip.appendChild(document.createTextNode('#' + tag));
        var remove = document.createElement('button');
        remove.type = 'button';
        remove.className = 'fireside-compose-tag-remove';
        remove.title = '删除标签（同时删除正文里的对应文字）';
        remove.textContent = '×';
        remove.addEventListener('click', function () {
          removeTagFromText(tag);
        });
        chip.appendChild(remove);
        tagsBox.appendChild(chip);
      });
    }

    /** chip 的 × 是“替你编辑正文”的快捷方式：删除该标签的所有出现，
     * 重新解析后 chip 自然消失——正文始终是唯一数据源。
     * 标签字符集不含正则元字符，无需转义。 */
    function removeTagFromText(tag) {
      if (editorWrap) {
        // 编辑器事务触发 onUpdate → onTextChange，chip 自动重投影
        editorWrap.removeTag(tag);
        return;
      }
      var pattern = new RegExp('(^|\\s)#' + tag + '(?=\\s|$)', 'g');
      textInput.value = textInput.value
        .replace(pattern, '$1')
        .replace(/ {2,}/g, ' ')
        .replace(/[ \t]+\n/g, '\n')
        .replace(/^[ \t]+/, '');
      onTextChange();
    }

    /** 光标左侧是否正处于未完成的 #片段（# 后 0..n 个合法字符、未敲空白） */
    function currentTagFragment() {
      if (editorWrap) {
        return editorWrap.getTagFragment();
      }
      var pos = textInput.selectionStart;
      if (pos == null) {
        return null;
      }
      var match = textInput.value.slice(0, pos).match(TAG_AT_CARET);
      return match ? match[2] : null;
    }

    function updateSuggest() {
      var fragment = currentTagFragment();
      if (fragment === null) {
        hideSuggest();
        return;
      }
      if (fragment === suggestFragment && !suggestBox.hidden) {
        return;
      }
      suggestFragment = fragment;
      clearTimeout(suggestTimer);
      // 停顿 200ms 再查，避免每敲一个字符都打接口
      suggestTimer = setTimeout(function () {
        fetchSuggest(fragment);
      }, 200);
    }

    function fetchSuggest(fragment) {
      var seq = ++suggestSeq;
      fetch(UC_TAGS_API + '?name=' + encodeURIComponent(fragment))
        .then(function (res) { return res.ok ? res.json() : []; })
        .then(function (list) {
          // 过期响应或光标语境已变化则丢弃
          if (seq !== suggestSeq || currentTagFragment() !== fragment) {
            return;
          }
          renderSuggest(Array.isArray(list) ? list.slice(0, 8) : []);
        })
        .catch(function () { /* 联想失败静默，不影响输入 */ });
    }

    function renderSuggest(list) {
      suggestItems = list;
      suggestIndex = -1;
      suggestBox.textContent = '';
      if (!list.length) {
        suggestBox.hidden = true;
        return;
      }
      list.forEach(function (tag) {
        var item = document.createElement('button');
        item.type = 'button';
        item.className = 'fireside-compose-suggest-item';
        item.textContent = '#' + tag;
        item.addEventListener('click', function () {
          applySuggest(tag);
        });
        suggestBox.appendChild(item);
      });
      suggestBox.hidden = false;
    }

    function syncSuggestHighlight() {
      Array.prototype.forEach.call(suggestBox.children, function (el, i) {
        el.classList.toggle('is-active', i === suggestIndex);
      });
    }

    function hideSuggest() {
      suggestBox.hidden = true;
      suggestBox.textContent = '';
      suggestItems = [];
      suggestIndex = -1;
      suggestFragment = null;
      clearTimeout(suggestTimer);
    }

    /** 选中候选：补全片段为完整标签并补上终止空格，光标落在空格后 */
    function applySuggest(tag) {
      if (editorWrap) {
        editorWrap.applyTagSuggest(tag);
        hideSuggest();
        syncTagChips();
        return;
      }
      var pos = textInput.selectionStart;
      var before = textInput.value.slice(0, pos);
      var after = textInput.value.slice(pos);
      var match = before.match(TAG_AT_CARET);
      if (!match) {
        hideSuggest();
        return;
      }
      var start = before.length - match[2].length;
      var newBefore = before.slice(0, start) + tag + ' ';
      textInput.value = newBefore + after;
      textInput.focus();
      textInput.setSelectionRange(newBefore.length, newBefore.length);
      hideSuggest();
      syncTagChips();
    }

    /** 联想条键盘通道：↑↓ 高亮、Enter 仅在有高亮时选中（否则照常换行）、
     * Esc 两段式（先收联想条，stopPropagation 拦住 document 上的关浮层） */
    function handleSuggestKeydown(e) {
      if (composing || e.isComposing || suggestBox.hidden) {
        return;
      }
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault();
        var count = suggestItems.length;
        if (!count) {
          return;
        }
        suggestIndex = (suggestIndex + (e.key === 'ArrowDown' ? 1 : -1) + count) % count;
        syncSuggestHighlight();
      } else if (e.key === 'Enter') {
        if (suggestIndex >= 0 && suggestIndex < suggestItems.length) {
          e.preventDefault();
          applySuggest(suggestItems[suggestIndex]);
        }
      } else if (e.key === 'Tab' && !e.shiftKey) {
        // Tab 确认：有高亮选高亮，无高亮选第一条（GitHub/VS Code 惯例）；
        // Shift+Tab 保持原生的反向移焦点
        if (suggestItems.length) {
          e.preventDefault();
          applySuggest(suggestItems[suggestIndex >= 0 ? suggestIndex : 0]);
        }
      } else if (e.key === 'Escape') {
        e.preventDefault();
        e.stopPropagation();
        hideSuggest();
      }
    }

    /* ===== Tiptap 编辑器（渐进增强）=====
     * bundle（fireside-editor.js，与瞬间官方编辑器同内核）首次打开浮层时
     * 懒加载；就绪后隐藏 textarea 换编辑器 + 工具栏，失败则纯文本路径兜底。
     * schema 只含 p/br/strong/em/s/code/a，粘贴的花哨内容自动降级为纯文本 */
    function ensureEditor() {
      if (editorLoadState) {
        return;
      }
      var src = composeBox.getAttribute('data-editor-src');
      if (!src) {
        editorLoadState = 'failed';
        return;
      }
      editorLoadState = 'loading';
      var script = document.createElement('script');
      script.src = src;
      script.onload = initEditor;
      script.onerror = function () {
        editorLoadState = 'failed';
        console.warn('[围炉] 编辑器加载失败，发布框以纯文本模式继续');
      };
      document.head.appendChild(script);
    }

    function initEditor() {
      if (typeof FiresideEditor === 'undefined') {
        editorLoadState = 'failed';
        return;
      }
      try {
        editorWrap = FiresideEditor.create({
          element: editorMount,
          placeholder: '这一刻的想法…',
          onUpdate: function () {
            onTextChange();
            // 每次编辑都可能改变撤销/重做可用态
            syncToolbar();
          },
          onSelectionUpdate: function () {
            updateSuggest();
            syncToolbar();
          },
          onKeyDown: editorSuggestKeydown,
          onPasteFiles: function (files) {
            if (submitting) {
              return true;
            }
            var images = files.filter(function (f) {
              return f.type.indexOf('image/') === 0;
            });
            if (!images.length) {
              return false;
            }
            images.forEach(addMedia);
            return true;
          }
        });
      } catch (err) {
        editorLoadState = 'failed';
        console.warn('[围炉] 编辑器初始化失败，发布框以纯文本模式继续', err);
        return;
      }
      editorLoadState = 'ready';
      // 接管 textarea：迁移已敲内容后切换显示（浮层未开也预初始化无妨）
      if (textInput.value) {
        editorWrap.setPlainText(textInput.value);
      }
      textInput.hidden = true;
      editorMount.hidden = false;
      toolbar.hidden = false;
      if (!composeBox.hidden) {
        editorWrap.focus();
      }
      syncTagChips();
      syncToolbar();
    }

    /** 正文纯文本投影：编辑器路径段落/硬换行均映射为 \n，与 textarea 语义一致 */
    function sourceText() {
      return editorWrap ? editorWrap.getText() : textInput.value;
    }

    function focusInput() {
      if (editorWrap) {
        editorWrap.focus();
      } else {
        textInput.focus();
      }
    }

    function syncToolbar() {
      if (!editorWrap || submitting) {
        return;
      }
      toolbarBtns.forEach(function (btn) {
        var fmt = btn.getAttribute('data-fmt');
        if (fmt === 'undo') {
          btn.disabled = !editorWrap.canUndo();
        } else if (fmt === 'redo') {
          btn.disabled = !editorWrap.canRedo();
        } else {
          btn.classList.toggle('is-active', editorWrap.isActive(fmt));
        }
      });
    }

    /** 编辑器键盘拦截：联想条可见时接管 ↑↓/Enter/Tab/Esc（语义同 textarea 版） */
    function editorSuggestKeydown(e) {
      if (e.isComposing || suggestBox.hidden) {
        return false;
      }
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        var count = suggestItems.length;
        if (count) {
          suggestIndex = (suggestIndex + (e.key === 'ArrowDown' ? 1 : -1) + count) % count;
          syncSuggestHighlight();
        }
        return true;
      }
      if (e.key === 'Enter') {
        if (suggestIndex >= 0 && suggestIndex < suggestItems.length) {
          applySuggest(suggestItems[suggestIndex]);
          return true;
        }
        return false;
      }
      if (e.key === 'Tab' && !e.shiftKey) {
        if (suggestItems.length) {
          applySuggest(suggestItems[suggestIndex >= 0 ? suggestIndex : 0]);
          return true;
        }
        return false;
      }
      if (e.key === 'Escape') {
        // 拦住冒泡，避免 document 层的 Esc 把整个浮层关掉（两段式退出）
        e.stopPropagation();
        hideSuggest();
        return true;
      }
      return false;
    }

    /** 链接按钮三态：光标在链接内→整段取消；选中文本是 URL→原地成链；
     * 其余（选中普通文字 / 无选中）→ 弹输入行 */
    function handleLinkButton() {
      if (editorWrap.isActive('link')) {
        editorWrap.unsetLink();
        return;
      }
      var selected = editorWrap.getSelectedText().replace(/^\s+|\s+$/g, '');
      var asUrl = normalizeUrl(selected);
      if (selected && isValidHttpUrl(asUrl)) {
        editorWrap.setLinkOnSelection(asUrl);
        return;
      }
      openLinkRow(!!selected);
    }

    function openLinkRow(hasSelection) {
      linkHasSelection = hasSelection;
      linkUrlInput.value = '';
      linkTextInput.value = '';
      // 带选区打开时显示文字即选区本身，不再要文字
      linkTextInput.hidden = hasSelection;
      linkRow.hidden = false;
      linkUrlInput.focus();
    }

    function closeLinkRow() {
      linkRow.hidden = true;
      linkHasSelection = false;
      if (editorWrap && !composeBox.hidden) {
        editorWrap.focus();
      }
    }

    function confirmLink() {
      if (!editorWrap) {
        return;
      }
      var url = normalizeUrl(linkUrlInput.value.replace(/^\s+|\s+$/g, ''));
      if (!isValidHttpUrl(url)) {
        showTip('链接地址需以 http(s):// 开头');
        return;
      }
      hideTip();
      if (linkHasSelection) {
        editorWrap.setLinkOnSelection(url);
      } else {
        var text = linkTextInput.value.replace(/^\s+|\s+$/g, '') || url;
        editorWrap.insertLinkText(url, text);
      }
      closeLinkRow();
      syncToolbar();
    }

    /** www. 开头补 https://，其余原样返回（协议校验交给 isValidHttpUrl） */
    function normalizeUrl(input) {
      if (/^www\./i.test(input)) {
        return 'https://' + input;
      }
      return input;
    }

    /** 只放行 http(s) 绝对链接，杜绝 javascript:/data: 注入 */
    function isValidHttpUrl(input) {
      if (!/^https?:\/\/\S+$/i.test(input)) {
        return false;
      }
      try {
        var parsed = new URL(input);
        return parsed.protocol === 'http:' || parsed.protocol === 'https:';
      } catch (e) {
        return false;
      }
    }

    /** 编辑器 HTML 后处理：把文本中已生效的 #标签 包装为官方格式的
     * a.tag 节点（与 textToHtml 的包装物一致）；链接内文本跳过 */
    function decorateTags(html, tags) {
      if (!html || !tags.length) {
        return html;
      }
      var tagSet = {};
      tags.forEach(function (t) { tagSet[t] = true; });
      var doc = new DOMParser().parseFromString(
        '<div id="root">' + html + '</div>', 'text/html');
      var root = doc.getElementById('root');
      var walker = doc.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
      var textNodes = [];
      var node;
      while ((node = walker.nextNode())) {
        if (!node.parentElement.closest('a')) {
          textNodes.push(node);
        }
      }
      var wrapPattern = new RegExp('(^|\\s)#(' + TAG_CHARS + '+)(?=\\s|$)', 'g');
      textNodes.forEach(function (textNode) {
        var value = textNode.nodeValue;
        var frag = null;
        var last = 0;
        var m;
        wrapPattern.lastIndex = 0;
        while ((m = wrapPattern.exec(value)) !== null) {
          if (!tagSet[m[2]]) {
            continue;
          }
          if (!frag) {
            frag = doc.createDocumentFragment();
          }
          var start = m.index + m[1].length;
          if (start > last) {
            frag.appendChild(doc.createTextNode(value.slice(last, start)));
          }
          var a = doc.createElement('a');
          a.className = 'tag';
          a.setAttribute('href', '/moments?tag=' + encodeURIComponent(m[2]));
          a.setAttribute('data-pjax', '');
          a.textContent = m[2];
          frag.appendChild(a);
          last = start + 1 + m[2].length;
        }
        if (frag) {
          if (last < value.length) {
            frag.appendChild(doc.createTextNode(value.slice(last)));
          }
          textNode.parentNode.replaceChild(frag, textNode);
        }
      });
      return root.innerHTML;
    }

    function handlePublish() {
      if (submitting || (drag && drag.active)) {
        return;
      }
      // 标签以“已敲空白终止”的原文判定；首尾空白仅在成文时清理，
      // tagSet 门禁保证清理不会弄丢末尾已生效的标签
      var rawText = sourceText();
      var contentTags = parseContentTags(rawText);
      var text = rawText.replace(/^\s+|\s+$/g, '');
      var hasText = editorWrap ? !editorWrap.isEmpty() : !!text;
      if (!hasText && !mediaItems.length) {
        showTip('写点什么，或添加一张图片');
        return;
      }
      // textarea 的 maxlength 编辑器路径没有，发表前统一兜底
      if (text.length > 5000) {
        showTip('正文最多 5000 字');
        return;
      }
      submitting = true;
      setComposeLocked(true);
      hideTip();
      // 仿微信朋友圈：点发表才把图片逐张上传，全部就位再创建瞬间；
      // 有失败则中止发表，再按“发表”只补传失败的（成功的已记住 permalink）
      uploadPendingMedia(function (failures) {
        if (failures.length) {
          submitting = false;
          setSubmitBusy(false);
          setComposeLocked(false);
          showUploadFailure(failures);
          return;
        }
        publishMoment(text, contentTags);
      });
    }

    /** 把还没有 permalink 的图片按九宫格槽位顺序逐张上传（微信同款：
     * 轮到哪张哪张亮“上传中…”，一张完成再传下一张）；中途失败不打断
     * 后续张，全部结束后统一回调失败错误列表 */
    function uploadPendingMedia(done) {
      var pending = mediaItems.filter(function (m) { return !m.url; });
      if (!pending.length) {
        done([]);
        return;
      }
      var total = pending.length;
      var finished = 0;
      var failures = [];
      var cursor = 0;
      pending.forEach(function (m) {
        m.failed = false;
        m.el.classList.remove('is-failed');
      });
      setSubmitBusy(true, '上传图片 0/' + total + '…');
      function next() {
        if (cursor >= pending.length) {
          return;
        }
        uploadOne(pending[cursor++], function (err) {
          finished++;
          if (err) {
            failures.push(err);
          }
          setSubmitBusy(true, '上传图片 ' + finished + '/' + total + '…');
          if (finished === total) {
            done(failures);
          } else {
            next();
          }
        });
      }
      next();
    }

    /** 单张图片经 Halo UC 附件接口上传，成功后把 permalink 记回 item */
    function uploadOne(item, cb) {
      item.uploading = true;
      item.el.classList.add('is-uploading');
      var form = new FormData();
      form.append('file', item.file);
      fetch(UC_UPLOAD_API, { method: 'POST', body: form })
        .then(function (res) {
          if (!res.ok) {
            throw Object.assign(new Error('HTTP ' + res.status), { status: res.status });
          }
          return res.json();
        })
        .then(function (attachment) {
          // 上传接口保证返回前已解析好 permalink（即图片最终 URL）
          var permalink = attachment && attachment.status && attachment.status.permalink;
          if (!permalink) {
            throw new Error('响应缺少 permalink');
          }
          item.url = permalink;
          item.uploading = false;
          item.el.classList.remove('is-uploading');
          cb(null);
        })
        .catch(function (err) {
          item.uploading = false;
          item.failed = true;
          item.el.classList.remove('is-uploading');
          item.el.classList.add('is-failed');
          console.warn('[围炉] 图片上传失败', err);
          cb(err || new Error('上传失败'));
        });
    }

    /** 权限/配置类错误给定向提示（文案与旧“选图即传”版一致），其余按张数汇总 */
    function showUploadFailure(failures) {
      var has403 = failures.some(function (e) { return e && e.status === 403; });
      var has400 = failures.some(function (e) { return e && e.status === 400; });
      if (has403) {
        showTip('没有上传附件的权限（可联系管理员开通），仍可发布纯文字');
      } else if (has400) {
        showTip('站点未配置个人中心的附件存储策略，请先在后台配置');
      } else {
        showTip(failures.length + ' 张图片上传失败，点“发表”可只重传失败的，也可移除后再发');
      }
    }

    function publishMoment(text, contentTags) {
      setSubmitBusy(true);
      // payload 结构与瞬间插件官方 UC 发布组件一致；
      // owner 与 approved 由服务端按当前用户和角色强制覆写；
      // raw 与 html 同为富文本 HTML（官方模型，后台编辑器直接装载 raw）。
      // 编辑器路径直接取 Tiptap HTML 再包装 #标签；textarea 兜底走 textToHtml
      var contentHtml = editorWrap
        ? (editorWrap.isEmpty() ? '' : decorateTags(editorWrap.getHTML(), contentTags))
        : textToHtml(text, contentTags);
      var moment = {
        apiVersion: 'moment.halo.run/v1alpha1',
        kind: 'Moment',
        metadata: { generateName: 'moment-', name: '' },
        spec: {
          content: {
            raw: contentHtml,
            html: contentHtml,
            // 走到这里所有图片必已上传成功，按九宫格顺序（可拖拽调整）出图
            medium: mediaItems.map(function (m) {
              return { type: 'PHOTO', url: m.url, originType: m.originType };
            })
          },
          releaseTime: new Date().toISOString(),
          owner: '',
          visible: isPrivate ? 'PRIVATE' : 'PUBLIC',
          tags: contentTags,
          approved: false
        }
      };
      fetch(UC_MOMENT_API, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(moment)
      })
        .then(function (res) {
          if (!res.ok) {
            throw Object.assign(new Error('HTTP ' + res.status), { status: res.status });
          }
          return res.json();
        })
        .then(function (created) {
          // 免审核角色的瞬间即时生效，刷新后出现在时间线首位；
          // 待审核的瞬间上不了时间线，提示后关闭浮层
          if (created && created.spec && created.spec.approved) {
            location.reload();
            return;
          }
          resetCompose();
          showTip('已提交，审核通过后展示', true);
          setTimeout(function () {
            closeCompose();
            hideTip();
          }, 1600);
        })
        .catch(function (err) {
          if (err && err.status === 403) {
            showTip('没有发布瞬间的权限');
          } else {
            showTip('发布失败，请稍后再试');
          }
          console.warn('[围炉] 发布瞬间失败', err);
        })
        .then(function () {
          submitting = false;
          setSubmitBusy(false);
          setComposeLocked(false);
        });
    }

    function setSubmitBusy(busy, label) {
      submitBtns.forEach(function (btn) {
        btn.disabled = busy;
        btn.textContent = busy ? (label || '发表中…') : '发表';
      });
    }

    /** 发表进行中锁住可编辑区（payload 采用点发表那一刻的快照，
     * 中途编辑不会生效，干脆按微信的做法禁掉；移除按钮与拖拽
     * 已在各自入口用 submitting 挡住） */
    function setComposeLocked(locked) {
      textInput.disabled = locked;
      if (editorWrap) {
        editorWrap.setEditable(!locked);
      }
      toolbarBtns.forEach(function (btn) {
        btn.disabled = locked;
      });
      visibleBtn.disabled = locked;
      addBtn.disabled = locked;
      if (!locked) {
        // 解锁后恢复撤销/重做按钮的真实可用态
        syncToolbar();
      }
    }

    function resetCompose() {
      textInput.value = '';
      if (editorWrap) {
        editorWrap.clear();
      }
      closeLinkRow();
      isPrivate = false;
      visibleValue.textContent = '公开';
      visibleBtn.classList.remove('is-private');
      mediaItems.forEach(function (m) {
        URL.revokeObjectURL(m.objectUrl);
        m.el.remove();
      });
      mediaItems = [];
      syncAddButton();
      syncTagChips();
      hideSuggest();
    }
  }
})();
