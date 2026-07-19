/**
 * 围炉 /fireside 页面脚本：点赞与评论区显隐切换。
 * 页面内容（含分页）全部由服务端渲染；评论组件由模板中的
 * <halo:comment> 官方标签渲染并自初始化，本脚本不感知其内部实现。
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
      // 剩余标签（混排行/不在段落里的散落标签）退化为纯文本
      var rest = content.querySelectorAll('a.tag');
      Array.prototype.forEach.call(rest, function (a) {
        a.replaceWith(document.createTextNode(a.textContent));
      });
    });
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
    if (!name || upvotedNames().indexOf(name) !== -1) {
      return;
    }
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
      });
  }

  function toggleComments(btn) {
    var card = btn.closest('.fireside-card');
    var box = card && card.querySelector('.fireside-comments');
    if (!box) {
      return;
    }
    box.hidden = !box.hidden;
    // 评论组件插件未装时 halo:comment 渲染为空，给一行提示
    if (!box.hidden && box.children.length === 0 && !box.textContent.trim()) {
      box.textContent = '评论不可用（站点未安装或未启用「评论组件」插件）';
    }
  }

  /** 详情页：评论区默认展开，按钮平滑滚动定位过去 */
  function scrollToComments(btn) {
    var card = btn.closest('.fireside-card');
    var box = card && card.querySelector('.fireside-comments');
    if (box) {
      box.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
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
    var commentBtn = e.target.closest('.fireside-comment-toggle');
    if (commentBtn) {
      toggleComments(commentBtn);
    }
  });

  applyLikedState();
  cleanContentTags();

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

  // 返回顶部：滚过一屏后出现
  var topBtn = document.getElementById('fs-top');
  if (topBtn) {
    var syncTopBtn = function () {
      topBtn.hidden = window.scrollY < window.innerHeight;
    };
    window.addEventListener('scroll', syncTopBtn, { passive: true });
    syncTopBtn();
    topBtn.addEventListener('click', function () {
      window.scrollTo({ top: 0, behavior: 'smooth' });
    });
  }
})();
