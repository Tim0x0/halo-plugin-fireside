<script lang="ts" setup>
import { axiosInstance } from '@halo-dev/api-client'
import {
  Dialog,
  Toast,
  VButton,
  VCard,
  VEmpty,
  VLoading,
  VPageHeader,
  VPagination,
  VSpace,
  vTooltip,
} from '@halo-dev/components'
import { utils } from '@halo-dev/ui-shared'
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import RiFireLine from '~icons/ri/fire-line'
import RiArrowLeftLine from '~icons/ri/arrow-left-line'
import RiRssLine from '~icons/ri/rss-line'
import MgRefresh2Line from '~icons/mingcute/refresh-2-line'
import MgPauseCircleLine from '~icons/mingcute/pause-circle-line'
import MgPlayCircleLine from '~icons/mingcute/play-circle-line'
import MgDelete2Line from '~icons/mingcute/delete-2-line'
import MgCheckLine from '~icons/mingcute/check-line'
import MgMultiselectLine from '~icons/mingcute/multiselect-line'
import MgMailLine from '~icons/mingcute/mail-line'
import MgMailOpenLine from '~icons/mingcute/mail-open-line'
import MgEyeCloseLine from '~icons/mingcute/eye-close-line'
import MgEye2Line from '~icons/mingcute/eye-2-line'
import MgExternalLinkLine from '~icons/mingcute/external-link-line'
import {
  PAUSED_ANNO,
  RSS_URL_ANNO,
  type ClearResponse,
  type FeedStatus,
  type MarkReadResponse,
  type FriendMoment,
  type Link,
  type ListResult,
  type SyncResponse,
} from '@/types'

const router = useRouter()

const links = ref<Link[]>([])
const statuses = ref<Map<string, FeedStatus>>(new Map())
const moments = ref<FriendMoment[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const selectedLinkName = ref<string>('')
const showHidden = ref(false)
const showUnread = ref(false)
// 批量选择：仅正常条目可被选中（作者已删除的不可标已读、不可隐藏）
const selecting = ref(false)
const selectedNames = ref<Set<string>>(new Set())

const loadingList = ref(false)
const markingAllRead = ref(false)
const loadingLinks = ref(false)
const syncingLink = ref(false)
const syncingAll = ref(false)
const togglingPause = ref(false)
const clearing = ref(false)
// 隐藏/恢复进行中的条目名集合，防止连点并发 PUT 撞乐观锁
const togglingNames = ref<Set<string>>(new Set())
// 列表请求序号：快切友链/Tab/翻页时丢弃过期响应，避免后发先至写错列表
let momentsRequestSeq = 0

// 同步与清空互斥：清空会重置计数，与同步并发会产生错乱结果（后端 409 兜底，这里前置禁用）
const mutating = computed(() => syncingAll.value || syncingLink.value || clearing.value)

// Halo 删除是先打标记异步真删，过滤掉删除中的条目避免闪现
const visibleMoments = computed(() =>
  moments.value.filter((m) => !m.metadata.deletionTimestamp),
)

const selectedLink = computed(() =>
  links.value.find((link) => link.metadata.name === selectedLinkName.value),
)

const selectedStatus = computed(() =>
  selectedLinkName.value ? statuses.value.get(selectedLinkName.value) : undefined,
)

const emptyTitle = computed(() => {
  if (showHidden) return '没有已隐藏的动态'
  if (showUnread) return '没有未读动态'
  return '暂无友链动态'
})

const selectedTitle = computed(() => {
  if (!selectedLink.value) return '全部动态'
  return displayNameOf(selectedLink.value)
})

const totalItemCount = computed(() =>
  links.value.reduce((sum, link) => sum + Number(itemCountOf(link)), 0),
)

const totalUnreadCount = computed(() =>
  links.value.reduce((sum, link) => sum + Number(unreadCountOf(link)), 0),
)

const failedCount = computed(
  () => links.value.filter((link) => statuses.value.get(link.metadata.name)?.spec?.lastError).length,
)

const selectedMeta = computed(() => {
  if (!selectedLink.value) {
    return `${links.value.length} 个订阅 / ${totalItemCount.value} 条动态 / ${totalUnreadCount.value} 条未读`
  }
  const spec = selectedStatus.value?.spec
  const parts = [`${spec?.itemCount ?? 0} 条动态`, `${spec?.unreadCount ?? 0} 条未读`]
  if (spec?.lastSuccessAt) {
    parts.push(`上次同步 ${formatDate(spec.lastSuccessAt)}`)
  }
  if (spec?.latestPublishedAt) {
    parts.push(`最新动态 ${formatDate(spec.latestPublishedAt)}`)
  }
  return parts.join(' / ')
})

interface StatusMeta {
  tone: 'success' | 'error' | 'muted' | 'warning'
  label: string
  description?: string
}

const selectedStatusMeta = computed<StatusMeta>(() => {
  if (!selectedLink.value) {
    if (!links.value.length) return { tone: 'muted', label: '暂无订阅' }
    if (failedCount.value) return { tone: 'error', label: `${failedCount.value} 个订阅同步失败` }
    return { tone: 'success', label: '订阅正常' }
  }
  if (isPaused(selectedLink.value)) {
    return { tone: 'warning', label: '已暂停' }
  }
  return statusMetaOf(statuses.value.get(selectedLinkName.value))
})

function statusMetaOf(status?: FeedStatus): StatusMeta {
  const spec = status?.spec
  if (!spec?.lastFetchedAt) {
    return { tone: 'muted', label: '未同步' }
  }
  if (spec.lastError) {
    return {
      tone: 'error',
      label: `同步失败 · 连续 ${spec.failureCount ?? 1} 次`,
      description: spec.lastError,
    }
  }
  return { tone: 'success', label: '正常' }
}

function displayNameOf(link: Link) {
  return link.spec?.displayName || link.metadata.name
}

/** 侧边栏第二行：站点主机名，取不到时退回完整地址 */
function siteLabelOf(link: Link) {
  const url = link.spec?.url
  if (!url) return ''
  try {
    return new URL(url).host
  } catch {
    return url
  }
}

function isPaused(link: Link) {
  return link.metadata.annotations?.[PAUSED_ANNO] === 'true'
}

// 侧边栏状态点只标异常：绿点叠在名称+计数旁像“未读”角标，正常态不显示；
// 暂停已有“暂停”文字标注，也不再加点
function statusDotClass(link: Link): string | null {
  if (isPaused(link)) return null
  const spec = statuses.value.get(link.metadata.name)?.spec
  if (!spec?.lastFetchedAt) return 'is-muted'
  return spec.lastError ? 'is-error' : null
}

function statusDotTitle(link: Link) {
  const meta = statusMetaOf(statuses.value.get(link.metadata.name))
  return meta.description ? `${meta.label}：${meta.description}` : meta.label
}

function itemCountOf(link: Link) {
  return statuses.value.get(link.metadata.name)?.spec?.itemCount ?? 0
}

function unreadCountOf(link: Link) {
  return statuses.value.get(link.metadata.name)?.spec?.unreadCount ?? 0
}

function formatDate(value?: string | null) {
  // 统一走 Console 的日期工具（YYYY-MM-DD HH:mm），仅补空值占位符
  return value ? utils.date.format(value) : '-'
}

function photosOf(moment: FriendMoment) {
  return (moment.spec.media ?? []).filter((m) => m.type === 'PHOTO').map((m) => m.url)
}

async function fetchLinks() {
  loadingLinks.value = true
  try {
    const [linksRes, statusRes] = await Promise.all([
      axiosInstance.get<ListResult<Link>>('/apis/core.halo.run/v1alpha1/links', {
        params: { size: 1000 },
      }),
      axiosInstance.get<ListResult<FeedStatus>>('/apis/fireside.timxs.com/v1alpha1/feedstatuses', {
        params: { size: 1000 },
      }),
    ])
    links.value = linksRes.data.items.filter((link) =>
      link.metadata.annotations?.[RSS_URL_ANNO]?.trim(),
    )
    statuses.value = new Map(statusRes.data.items.map((s) => [s.metadata.name, s]))
    if (selectedLinkName.value && !links.value.some((l) => l.metadata.name === selectedLinkName.value)) {
      selectedLinkName.value = ''
    }
  } catch (e) {
    Toast.error(errorMessage(e, '加载订阅列表失败'))
  } finally {
    loadingLinks.value = false
  }
}

async function fetchMoments() {
  const seq = ++momentsRequestSeq
  loadingList.value = true
  try {
    // fieldSelector 多条件必须用重复参数（fieldSelector=a&fieldSelector=b），
    // 逗号拼接会被当成单个值解析导致 500
    const query = new URLSearchParams()
    query.append('page', String(page.value))
    query.append('size', String(size.value))
    query.append('sort', 'spec.pubDate,desc')
    query.append('fieldSelector', `spec.hidden=${showHidden.value}`)
    // 未读只看正常条目里没读过的；作者已删除标的是红点，不进这个筛选
    if (showUnread.value) {
      query.append('fieldSelector', 'spec.read=false')
      query.append('fieldSelector', 'spec.authorDeleted=false')
    }
    if (selectedLinkName.value) {
      query.append('fieldSelector', `spec.linkName=${selectedLinkName.value}`)
    }
    const { data } = await axiosInstance.get<ListResult<FriendMoment>>(
      `/apis/fireside.timxs.com/v1alpha1/friendmoments?${query.toString()}`,
    )
    // 过期响应：用户已切换范围/页码，丢弃
    if (seq !== momentsRequestSeq) {
      return
    }
    moments.value = data.items
    total.value = data.total
    // 页码超界（如隐藏/删除掉当前页最后一条后）时回退到最后一个有效页重取，
    // 否则用户停在空页且分页控件可能因 total 缩小而消失
    const maxPage = Math.max(Math.ceil(data.total / size.value), 1)
    if (page.value > maxPage) {
      page.value = maxPage
      return fetchMoments()
    }
  } catch (e) {
    if (seq !== momentsRequestSeq) {
      return
    }
    Toast.error(errorMessage(e, '加载动态列表失败'))
  } finally {
    if (seq === momentsRequestSeq) {
      loadingList.value = false
    }
  }
}

async function reload() {
  await Promise.all([fetchLinks(), fetchMoments()])
}

function selectLink(name: string) {
  if (selectedLinkName.value === name) return
  selectedLinkName.value = name
  page.value = 1
  clearSelection()
  fetchMoments()
}

function selectTab(tab: 'all' | 'unread' | 'hidden') {
  const hidden = tab === 'hidden'
  const unread = tab === 'unread'
  if (showHidden.value === hidden && showUnread.value === unread) return
  showHidden.value = hidden
  showUnread.value = unread
  page.value = 1
  clearSelection()
  fetchMoments()
}

/** 官方 VPagination 的 change：page/size 已由 v-model 写回，这里只负责拉数 */
function onPaginationChange() {
  fetchMoments()
}

async function sync(name: string, all: boolean) {
  const loading = all ? syncingAll : syncingLink
  loading.value = true
  try {
    const { data } = await axiosInstance.post<SyncResponse>(
      `/apis/console.api.fireside.timxs.com/v1alpha1/links/${name}/sync`,
    )
    showSyncSummary(data, all)
    await reload()
  } catch (e) {
    Toast.error(errorMessage(e, '同步请求失败'))
  } finally {
    loading.value = false
  }
}

/**
 * 同步结果里数量为 0 的项不出现；三项都是 0 返回空串，由调用方说「没有新内容」。
 */
function changeSummary(summary: SyncResponse): string {
  const trimmed = summary.results.reduce((sum, r) => sum + (r.trimmed || 0), 0)
  return [
    summary.createdCount ? `新增 ${summary.createdCount} 条` : '',
    summary.updatedCount ? `更新 ${summary.updatedCount} 条` : '',
    trimmed ? `清理 ${trimmed} 条旧数据` : '',
  ]
    .filter(Boolean)
    .join('，')
}

function showSyncSummary(summary: SyncResponse, all: boolean) {
  if (!summary.total) {
    Toast.info('没有可同步的订阅')
    return
  }
  const changes = changeSummary(summary)
  const content = changes || '没有新内容'
  if (!summary.failureCount) {
    Toast.success(`同步完成，${content}`)
    return
  }
  const failText = summary.results
    .filter((r) => r.error)
    .map((r) => (all ? `${r.displayName}：${r.error}` : r.error))
    .join('；')
  if (summary.failureCount === summary.total) {
    Toast.error(`同步失败：${failText}`)
    return
  }
  // 部分失败只在全部同步时出现；没有变更就不追加「没有新内容」，避免淹没失败原因
  Toast.warning(
    `同步完成：成功 ${summary.successCount} 个，失败 ${summary.failureCount} 个（${failText}）${changes ? `，${changes}` : ''}`,
  )
}

async function togglePause() {
  const link = selectedLink.value
  if (!link || togglingPause.value) return
  togglingPause.value = true
  try {
    // 取最新版本再改注解，避免覆盖他人修改
    const { data: latest } = await axiosInstance.get<Link>(
      `/apis/core.halo.run/v1alpha1/links/${link.metadata.name}`,
    )
    const annotations = { ...(latest.metadata.annotations || {}) }
    const pausing = annotations[PAUSED_ANNO] !== 'true'
    if (pausing) {
      annotations[PAUSED_ANNO] = 'true'
    } else {
      // 恢复时直接删除注解而非写 "false"——注解值域保持"只有 true 或不存在"，
      // 避免链接编辑表单的 checkbox 对字符串 "false" 产生歧义
      delete annotations[PAUSED_ANNO]
    }
    latest.metadata.annotations = annotations
    await axiosInstance.put(`/apis/core.halo.run/v1alpha1/links/${link.metadata.name}`, latest)
    Toast.success(pausing ? '已暂停该订阅' : '已恢复该订阅')
    await fetchLinks()
  } catch (e) {
    Toast.error(errorMessage(e, '操作失败'))
  } finally {
    togglingPause.value = false
  }
}

// 同一友链的计数写回串行化，避免连点时并行 GET 读到同一旧值、少减/少加
const countWriteTails = new Map<string, Promise<void>>()

/**
 * 隐藏/恢复/已读变更后把 FeedStatus 的展示数与未读数持久化。
 * 同 link 上排队执行；每次取最新版本再 PUT。失败则整表重拉状态。
 */
function persistCountDelta(linkName: string, itemDelta: number, unreadDelta: number): Promise<void> {
  const prev = countWriteTails.get(linkName) ?? Promise.resolve()
  const next = prev
    .catch(() => {
      /* 上一笔失败不阻断本笔 */
    })
    .then(async () => {
      try {
        const { data: latest } = await axiosInstance.get<FeedStatus>(
          `/apis/fireside.timxs.com/v1alpha1/feedstatuses/${linkName}`,
        )
        if (!latest.spec) {
          latest.spec = { linkName }
        }
        latest.spec.itemCount = Math.max(0, Number(latest.spec.itemCount ?? 0) + itemDelta)
        latest.spec.unreadCount = Math.max(0, Number(latest.spec.unreadCount ?? 0) + unreadDelta)
        const { data: updated } = await axiosInstance.put<FeedStatus>(
          `/apis/fireside.timxs.com/v1alpha1/feedstatuses/${linkName}`,
          latest,
        )
        statuses.value.set(linkName, updated)
        statuses.value = new Map(statuses.value)
      } catch {
        // 计数写失败不阻断操作本身；重拉以恢复与后端一致
        await fetchLinks()
      }
    })
  countWriteTails.set(linkName, next)
  return next
}

async function toggleHidden(moment: FriendMoment) {
  const name = moment.metadata.name
  if (togglingNames.value.has(name)) return
  togglingNames.value.add(name)
  try {
    const { data: latest } = await axiosInstance.get<FriendMoment>(
      `/apis/fireside.timxs.com/v1alpha1/friendmoments/${name}`,
    )
    // 与目标 Tab 对齐：已是目标状态则只刷新列表，避免重复 ±1
    const wantHidden = !showHidden.value
    const isHidden = Boolean(latest.spec.hidden)
    if (isHidden === wantHidden) {
      await fetchMoments()
      return
    }
    latest.spec.hidden = wantHidden
    await axiosInstance.put(
      `/apis/fireside.timxs.com/v1alpha1/friendmoments/${name}`,
      latest,
    )
    const linkName = latest.spec.linkName
    // 作者已删除的条目不进前台，不占展示数与未读数，隐藏或恢复都不改这两个计数
    if (linkName && !latest.spec.authorDeleted) {
      // 未读条目被隐藏时未读数一并减一，恢复时加回
      const unreadDelta = latest.spec.read ? 0 : wantHidden ? -1 : 1
      await persistCountDelta(linkName, wantHidden ? -1 : 1, unreadDelta)
    }
    Toast.success(wantHidden ? '已隐藏' : '已恢复')
    await fetchMoments()
  } catch (e) {
    Toast.error(errorMessage(e, '操作失败'))
  } finally {
    togglingNames.value.delete(name)
  }
}

/** 标为已读/未读：只改 read，展示数不变，未读数按状态变化增减 */
async function toggleRead(moment: FriendMoment) {
  const name = moment.metadata.name
  if (togglingNames.value.has(name)) return
  togglingNames.value.add(name)
  try {
    const { data: latest } = await axiosInstance.get<FriendMoment>(
      `/apis/fireside.timxs.com/v1alpha1/friendmoments/${name}`,
    )
    const wantRead = !latest.spec.read
    latest.spec.read = wantRead
    await axiosInstance.put(`/apis/fireside.timxs.com/v1alpha1/friendmoments/${name}`, latest)
    const linkName = latest.spec.linkName
    if (linkName && !latest.spec.hidden && !latest.spec.authorDeleted) {
      await persistCountDelta(linkName, 0, wantRead ? -1 : 1)
    }
    await fetchMoments()
  } catch (e) {
    Toast.error(errorMessage(e, '操作失败'))
  } finally {
    togglingNames.value.delete(name)
  }
}

function clearSelection() {
  selectedNames.value = new Set()
}

function toggleSelecting() {
  selecting.value = !selecting.value
  clearSelection()
}

function toggleSelected(name: string) {
  const next = new Set(selectedNames.value)
  if (next.has(name)) {
    next.delete(name)
  } else {
    next.add(name)
  }
  selectedNames.value = next
}

const selectableMoments = computed(() =>
  visibleMoments.value.filter((m) => !m.spec.authorDeleted),
)

const allSelected = computed(
  () =>
    selectableMoments.value.length > 0 &&
    selectableMoments.value.every((m) => selectedNames.value.has(m.metadata.name)),
)

function toggleSelectAll() {
  selectedNames.value = allSelected.value
    ? new Set()
    : new Set(selectableMoments.value.map((m) => m.metadata.name))
}

/**
 * 批量标为已读或隐藏。逐条取最新版本再写，避免覆盖同步刚补上的内容；
 * 计数按每个友链汇总后各写一次。
 */
/**
 * 全部标为已读：作用于当前选中的作者，未选作者时作用于全部，含未加载的更早条目。
 * 一次请求交给后端批量标记并归零未读数，避免逐条 GET+PUT 的请求风暴。
 */
async function markAllRead() {
  if (markingAllRead.value) return
  markingAllRead.value = true
  const name = selectedLinkName.value || '-'
  try {
    const { data } = await axiosInstance.post<MarkReadResponse>(
      `/apis/console.api.fireside.timxs.com/v1alpha1/links/${name}/mark-read`,
    )
    Toast.success(data.updatedCount ? `已读 ${data.updatedCount} 条` : '没有未读')
    await reload()
  } catch (e) {
    Toast.error(errorMessage(e, '操作失败'))
  } finally {
    markingAllRead.value = false
  }
}

async function batchUpdate(action: 'read' | 'hide') {
  const targets = visibleMoments.value.filter((m) => selectedNames.value.has(m.metadata.name))
  if (!targets.length) return
  const itemDelta = new Map<string, number>()
  const unreadDelta = new Map<string, number>()
  let done = 0
  for (const moment of targets) {
    try {
      const { data: latest } = await axiosInstance.get<FriendMoment>(
        `/apis/fireside.timxs.com/v1alpha1/friendmoments/${moment.metadata.name}`,
      )
      const linkName = latest.spec.linkName
      if (action === 'read') {
        if (latest.spec.read) continue
        latest.spec.read = true
        if (linkName && !latest.spec.hidden && !latest.spec.authorDeleted) {
          unreadDelta.set(linkName, (unreadDelta.get(linkName) ?? 0) - 1)
        }
      } else {
        if (latest.spec.hidden || latest.spec.authorDeleted) continue
        latest.spec.hidden = true
        if (linkName) {
          itemDelta.set(linkName, (itemDelta.get(linkName) ?? 0) - 1)
          if (!latest.spec.read) {
            unreadDelta.set(linkName, (unreadDelta.get(linkName) ?? 0) - 1)
          }
        }
      }
      await axiosInstance.put(
        `/apis/fireside.timxs.com/v1alpha1/friendmoments/${moment.metadata.name}`,
        latest,
      )
      done++
    } catch {
      // 单条失败跳过，其余继续；计数以实际写成功的为准
    }
  }
  const linkNames = new Set([...itemDelta.keys(), ...unreadDelta.keys()])
  for (const linkName of linkNames) {
    await persistCountDelta(linkName, itemDelta.get(linkName) ?? 0, unreadDelta.get(linkName) ?? 0)
  }
  clearSelection()
  Toast.success(`已隐藏 ${done} 条`)
  await fetchMoments()
}

function clearMoments() {
  const scope = selectedLink.value ? `「${selectedTitle.value}」` : '全部友链'
  Dialog.warning({
    title: '清空友链动态',
    description: `确认删除${scope}的所有已抓取动态（含已隐藏）吗？订阅本身不受影响，下次同步会重新抓取最新内容。`,
    confirmType: 'danger',
    onConfirm: async () => {
      clearing.value = true
      try {
        // 后端批量清空并归零计数：避免前端逐条 DELETE 的请求风暴，
        // 也保证清空后侧边栏计数立即准确
        const { data } = await axiosInstance.post<ClearResponse>(
          `/apis/console.api.fireside.timxs.com/v1alpha1/links/${selectedLinkName.value || '-'}/clear-moments`,
        )
        Toast.success(`已清空 ${data.deletedCount} 条`)
        page.value = 1
        await reload()
      } catch (e) {
        Toast.error(errorMessage(e, '清空失败'))
      } finally {
        clearing.value = false
      }
    },
  })
}

function errorMessage(e: unknown, fallback: string) {
  const detail = (e as { response?: { data?: { detail?: string; title?: string } } })?.response?.data
  return detail?.detail || detail?.title || fallback
}

onMounted(reload)
</script>

<template>
  <VPageHeader title="围炉订阅">
    <template #icon>
      <RiFireLine />
    </template>
    <template #actions>
      <VSpace>
        <VButton size="sm" @click="router.push({ path: '/links' })">
          <template #icon>
            <RiArrowLeftLine />
          </template>
          返回链接
        </VButton>
      </VSpace>
    </template>
  </VPageHeader>

  <div class="fs-page">
    <div class="fs-workspace">
      <!-- 左侧：订阅侧边栏 -->
      <aside class="fs-sidebar">
        <div class="fs-sidebar__header">
          <span class="fs-sidebar__title">
            <span>RSS</span>
            <strong>订阅</strong>
          </span>
          <span class="fs-sidebar__count">{{ links.length }}</span>
        </div>
        <div class="fs-sidebar__body">
          <button
            type="button"
            class="fs-source"
            :class="{ 'is-active': !selectedLinkName }"
            @click="selectLink('')"
          >
            <span class="fs-source__icon">
              <RiRssLine />
            </span>
            <span class="fs-source__main">
              <span class="fs-source__name">全部动态</span>
              <span class="fs-source__site">所有订阅</span>
            </span>
            <span v-if="totalUnreadCount" class="fs-source__unread">{{ totalUnreadCount }}</span>
          </button>
          <VLoading v-if="loadingLinks && !links.length" />
          <template v-else>
            <button
              v-for="link in links"
              :key="link.metadata.name"
              type="button"
              class="fs-source"
              :class="{ 'is-active': selectedLinkName === link.metadata.name }"
              @click="selectLink(link.metadata.name)"
            >
              <img
                v-if="link.spec?.logo"
                class="fs-source__logo"
                :src="link.spec.logo"
                alt=""
                loading="lazy"
                referrerpolicy="strict-origin-when-cross-origin"
              />
              <span v-else class="fs-source__icon">
                <RiRssLine />
              </span>
              <span class="fs-source__main">
                <span class="fs-source__name">
                  {{ displayNameOf(link) }}
                  <em v-if="isPaused(link)" class="fs-source__paused">暂停</em>
                </span>
                <span v-if="siteLabelOf(link)" class="fs-source__site">{{ siteLabelOf(link) }}</span>
              </span>
              <span
                v-if="statusDotClass(link)"
                v-tooltip="statusDotTitle(link)"
                class="fs-dot"
                :class="statusDotClass(link)"
              ></span>
              <span v-if="unreadCountOf(link)" class="fs-source__unread">{{
                unreadCountOf(link)
              }}</span>
            </button>
            <div v-if="!links.length" class="fs-sidebar__empty">
              还没有订阅。在「链接」中编辑友链，填写“动态 RSS 地址”即可订阅。
            </div>
          </template>
        </div>
      </aside>

      <!-- 右侧：概览 + 工具栏 + 条目流 -->
      <div class="fs-main">
        <div class="fs-brief">
          <div class="fs-brief__copy">
            <span class="fs-brief__eyebrow">围炉动态</span>
            <div class="fs-brief__heading">
              <strong class="fs-brief__title">{{ selectedTitle }}</strong>
              <span
                v-tooltip="selectedStatusMeta.description"
                class="fs-badge"
                :class="`is-${selectedStatusMeta.tone}`"
              >
                <span class="fs-badge__dot"></span>
                {{ selectedStatusMeta.label }}
              </span>
            </div>
            <span class="fs-brief__meta">{{ selectedMeta }}</span>
            <span v-if="selectedStatusMeta.description" class="fs-brief__error">
              {{ selectedStatusMeta.description }}
            </span>
          </div>
        </div>

        <div class="fs-toolbar">
          <div class="fs-tabs">
            <button
              type="button"
              class="fs-tab"
              :class="{ 'is-active': !showHidden && !showUnread }"
              @click="selectTab('all')"
            >
              全部
            </button>
            <button
              type="button"
              class="fs-tab"
              :class="{ 'is-active': showUnread }"
              @click="selectTab('unread')"
            >
              未读
            </button>
            <button
              type="button"
              class="fs-tab"
              :class="{ 'is-active': showHidden }"
              @click="selectTab('hidden')"
            >
              已隐藏
            </button>
          </div>
          <div class="fs-toolbar__actions">
            <VSpace>
              <VButton
                v-if="selectedLink"
                v-permission="['plugin:fireside:manage']"
                size="sm"
                :loading="syncingLink"
                :disabled="mutating || isPaused(selectedLink)"
                @click="sync(selectedLinkName, false)"
              >
                <template #icon>
                  <MgRefresh2Line />
                </template>
                同步当前
              </VButton>
              <VButton
                v-else
                v-permission="['plugin:fireside:manage']"
                size="sm"
                :disabled="!links.length || mutating"
                :loading="syncingAll"
                @click="sync('-', true)"
              >
                <template #icon>
                  <MgRefresh2Line />
                </template>
                同步全部
              </VButton>
              <VButton
                v-if="selectedLink"
                v-permission="['plugin:fireside:manage']"
                size="sm"
                :loading="togglingPause"
                @click="togglePause"
              >
                <template #icon>
                  <MgPlayCircleLine v-if="isPaused(selectedLink)" />
                  <MgPauseCircleLine v-else />
                </template>
                {{ isPaused(selectedLink) ? '恢复同步' : '暂停同步' }}
              </VButton>
              <VButton
                v-permission="['plugin:fireside:manage']"
                size="sm"
                type="danger"
                ghost
                :loading="clearing"
                :disabled="mutating"
                @click="clearMoments"
              >
                <template #icon>
                  <MgDelete2Line />
                </template>
                清空{{ selectedLink ? '当前' : '全部' }}
              </VButton>
              <VButton size="sm" ghost :loading="loadingList" @click="reload()">
                <template #icon>
                  <MgRefresh2Line />
                </template>
                刷新列表
              </VButton>
              <VButton
                v-if="!showHidden"
                v-permission="['plugin:fireside:manage']"
                size="sm"
                ghost
                :loading="markingAllRead"
                :disabled="mutating || markingAllRead"
                @click="markAllRead"
              >
                <template #icon>
                  <MgCheckLine />
                </template>
                全部标为已读
              </VButton>
              <VButton
                v-if="!showHidden"
                v-permission="['plugin:fireside:manage']"
                size="sm"
                ghost
                :type="selecting ? 'primary' : 'default'"
                :disabled="!selectableMoments.length"
                @click="toggleSelecting"
              >
                <template #icon>
                  <MgMultiselectLine />
                </template>
                {{ selecting ? '取消选择' : '批量选择' }}
              </VButton>
            </VSpace>
          </div>
        </div>

        <!-- 列表区对齐官方：VCard body 放内容，footer 放 VPagination（顶部分割线 + 12/16 内边距） -->
        <VCard :body-class="['!p-0']">
          <div v-if="selecting && selectableMoments.length" class="fs-batch">
            <label class="fs-batch__all">
              <input type="checkbox" :checked="allSelected" @change="toggleSelectAll" />
              全选本页
            </label>
            <span class="fs-batch__count">已选 {{ selectedNames.size }} 条</span>
            <VButton
              v-if="!showHidden"
              size="sm"
              :disabled="!selectedNames.size"
              @click="batchUpdate('hide')"
            >
              隐藏所选
            </VButton>
          </div>
          <VLoading v-if="loadingList && !visibleMoments.length" />
          <VEmpty
            v-else-if="!visibleMoments.length"
            :title="emptyTitle"
            :message="
              showHidden
                ? '在「全部」列表中隐藏的条目会出现在这里'
                : selectedLink && isPaused(selectedLink)
                  ? '该订阅已暂停同步，恢复后再同步即可抓取最新动态'
                  : '填写友链的动态 RSS 地址后，等待定时同步或点击同步按钮'
            "
          >
            <template v-if="!showHidden" #actions>
              <VButton
                v-if="selectedLink"
                v-permission="['plugin:fireside:manage']"
                type="secondary"
                :loading="syncingLink"
                :disabled="mutating || isPaused(selectedLink)"
                @click="sync(selectedLinkName, false)"
              >
                立即同步
              </VButton>
              <VButton
                v-else
                v-permission="['plugin:fireside:manage']"
                type="secondary"
                :loading="syncingAll"
                :disabled="!links.length || mutating"
                @click="sync('-', true)"
              >
                立即同步
              </VButton>
            </template>
          </VEmpty>
          <ul v-else class="fs-list">
            <li v-for="moment in visibleMoments" :key="moment.metadata.name" class="fs-item">
              <label v-if="selecting && !moment.spec.authorDeleted" class="fs-item__check">
                <input
                  type="checkbox"
                  :checked="selectedNames.has(moment.metadata.name)"
                  @change="toggleSelected(moment.metadata.name)"
                />
              </label>
              <!-- 头像仅在「全部动态」混排时显示，选中单个友链后来源已明确、行更紧凑 -->
              <template v-if="!selectedLinkName">
                <img
                  v-if="moment.spec.logo"
                  class="fs-item__avatar"
                  :src="moment.spec.logo"
                  alt=""
                  loading="lazy"
                  referrerpolicy="strict-origin-when-cross-origin"
                />
                <div v-else class="fs-item__avatar fs-item__avatar--placeholder">
                  {{ (moment.spec.author || '?').slice(0, 1) }}
                </div>
              </template>
              <div class="fs-item__body">
                <div class="fs-item__head">
                  <span
                    v-if="moment.spec.authorDeleted"
                    v-tooltip="'作者已删除'"
                    class="fs-state fs-state--deleted"
                  ></span>
                  <span
                    v-else-if="!moment.spec.read && !showHidden"
                    v-tooltip="'未读'"
                    class="fs-state fs-state--unread"
                  ></span>
                  <span class="fs-item__author">{{ moment.spec.author }}</span>
                  <span class="fs-item__time">{{ formatDate(moment.spec.pubDate) }}</span>
                </div>
                <div v-if="moment.spec.title" class="fs-item__title">
                  {{ moment.spec.title }}
                </div>
                <p v-if="moment.spec.contentText" class="fs-item__text">
                  {{ moment.spec.contentText }}
                </p>
                <div v-if="photosOf(moment).length" class="fs-item__images">
                  <img
                    v-for="img in photosOf(moment).slice(0, 9)"
                    :key="img"
                    :src="img"
                    alt=""
                    referrerpolicy="strict-origin-when-cross-origin"
                    loading="lazy"
                  />
                </div>
              </div>
              <div class="fs-item__actions">
                <VButton
                  v-if="!moment.spec.authorDeleted"
                  v-permission="['plugin:fireside:manage']"
                  v-tooltip="moment.spec.read ? '标为未读' : '标为已读'"
                  size="sm"
                  ghost
                  @click="toggleRead(moment)"
                >
                  <template #icon>
                    <MgMailOpenLine v-if="moment.spec.read" />
                    <MgMailLine v-else />
                  </template>
                </VButton>
                <!-- 外链必须是原生 a：VButton 没有 tag prop，tag="a" 实际仍渲染成 button，href 不跳转 -->
                <a
                  v-if="moment.spec.postLink"
                  v-tooltip="'查看原文'"
                  class="fs-item__origin"
                  :href="moment.spec.postLink"
                  target="_blank"
                  rel="noopener noreferrer"
                  aria-label="查看原文"
                >
                  <MgExternalLinkLine />
                </a>
                <VButton
                  v-if="!moment.spec.authorDeleted"
                  v-permission="['plugin:fireside:manage']"
                  v-tooltip="showHidden ? '恢复' : '隐藏'"
                  size="sm"
                  ghost
                  @click="toggleHidden(moment)"
                >
                  <template #icon>
                    <MgEye2Line v-if="showHidden" />
                    <MgEyeCloseLine v-else />
                  </template>
                </VButton>
              </div>
            </li>
          </ul>

          <template #footer>
            <VPagination
              v-model:page="page"
              v-model:size="size"
              :total="total"
              :size-options="[20, 30, 50, 100]"
              page-label="页"
              size-label="条 / 页"
              :total-label="`共 ${total} 条`"
              @change="onPaginationChange"
            />
          </template>
        </VCard>
      </div>
    </div>
  </div>
</template>

<style scoped>
.fs-page {
  /* 对齐 Halo Console：卡片描边 #eaecf0、主色 #4CCBA0、内容区外边距 16px */
  --fs-border: #eaecf0;
  --fs-primary: #4ccba0;
  --fs-text: #18181b;
  --fs-text-secondary: #52525b;
  --fs-text-muted: #71717a;
  --fs-shadow: 0 1px 2px rgb(15 23 42 / 0.04);
  margin: 0;
  padding: 1rem;
}

.fs-workspace {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 1rem;
  align-items: stretch;
}

@media (min-width: 1024px) {
  .fs-workspace {
    flex-direction: row;
    align-items: flex-start;
  }
}

/* ── 侧边栏 ─────────────────────────────── */
.fs-sidebar {
  width: 100%;
  flex: none;
  overflow: hidden;
  border: 1px solid var(--fs-border);
  border-radius: 8px;
  background: #fff;
  box-shadow: var(--fs-shadow);
}

@media (min-width: 1024px) {
  .fs-sidebar {
    position: sticky;
    top: 1rem;
    /* 宽度对齐链接插件订阅侧边栏（18rem） */
    width: 18rem;
    max-height: calc(100vh - 2rem);
  }
}

/* 标题区对齐链接插件：两行标题（眉题 + 加粗）配描边计数胶囊，无分割线 */
.fs-sidebar__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  padding: 0.75rem 0.75rem 0;
}

.fs-sidebar__title {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 0.125rem;
}

.fs-sidebar__title span {
  color: #71717a;
  font-size: 0.6875rem;
  font-weight: 650;
  line-height: 0.875rem;
}

.fs-sidebar__title strong {
  color: #18181b;
  font-size: 0.9375rem;
  font-weight: 650;
  line-height: 1.25rem;
}

.fs-sidebar__count {
  color: #71717a;
  font-size: 0.75rem;
  font-weight: 600;
  line-height: 1rem;
  font-variant-numeric: tabular-nums;
}

.fs-sidebar__body {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  padding: 0.75rem;
  overflow-y: auto;
}

.fs-source {
  position: relative;
  display: flex;
  width: 100%;
  align-items: center;
  gap: 0.625rem;
  /* 尺寸与边框对齐链接插件订阅项：8px 圆角、浅灰底、透明描边 */
  border: 1px solid transparent;
  border-radius: 8px;
  background: #fafafa;
  padding: 0.625rem;
  text-align: left;
  font-size: 0.875rem;
  color: #3f3f46;
  cursor: pointer;
  transition:
    background-color 0.18s ease,
    border-color 0.18s ease,
    box-shadow 0.18s ease,
    color 0.18s ease;
}

.fs-source:hover {
  background: #f4f4f5;
  color: #18181b;
}

/* 选中态对齐链接插件：描边 + 渐变底，不用卡片投影 */
.fs-source.is-active {
  border-color: #d4d4d8;
  background: linear-gradient(180deg, #fff, #f4f4f5);
  color: #18181b;
  box-shadow:
    inset 0 0 0 1px rgb(255 255 255 / 0.92),
    0 1px 2px rgb(15 23 42 / 0.06);
}

.fs-source.is-active .fs-source__site {
  color: #52525b;
}

.fs-source__logo {
  width: 1.875rem;
  height: 1.875rem;
  flex: none;
  border-radius: 7px;
  object-fit: cover;
  border: 1px solid #e5e7eb;
  background: #fff;
}

.fs-source__icon {
  display: inline-flex;
  width: 1.875rem;
  height: 1.875rem;
  flex: none;
  align-items: center;
  justify-content: center;
  border: 1px solid #e5e7eb;
  border-radius: 7px;
  background: #fff;
  color: #52525b;
  font-size: 0.875rem;
}

.fs-source__main {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 0.125rem;
  min-width: 0;
}

.fs-source__name {
  overflow: hidden;
  color: inherit;
  font-size: 0.875rem;
  font-weight: 650;
  line-height: 1.25rem;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fs-source__site {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 0.75rem;
  font-weight: 400;
  line-height: 1rem;
  color: #71717a;
}

.fs-source__paused {
  margin-left: 0.25rem;
  font-style: normal;
  font-size: 0.6875rem;
  font-weight: 400;
  color: #ea580c;
}

/* 未读数保留绿色徽章，不用链接插件的纯文字计数 */
.fs-source__unread {
  flex: none;
  min-width: 1rem;
  padding: 0 0.3125rem;
  border-radius: 999px;
  background: #22c55e;
  font-size: 0.6875rem;
  font-weight: 600;
  line-height: 1rem;
  text-align: center;
  color: #fff;
  font-variant-numeric: tabular-nums;
}

.fs-sidebar__empty {
  padding: 0.75rem 0.625rem;
  font-size: 0.8125rem;
  line-height: 1.5;
  color: var(--fs-text-muted);
}

/* ── 状态点（侧边栏，仅异常态显示）───────────── */
.fs-dot {
  width: 0.375rem;
  height: 0.375rem;
  flex: none;
  border-radius: 50%;
  background: #d4d4d8;
}

.fs-dot.is-error {
  background: #ef4444;
}

/* ── 主区 ─────────────────────────────── */
.fs-main {
  flex: 1;
  min-width: 0;
  width: 100%;
}

/* 概览条：对齐链接订阅页 feed-brief 的双层纹理质感 */
.fs-brief {
  display: flex;
  min-width: 0;
  align-items: flex-end;
  justify-content: space-between;
  gap: 1rem;
  border: 1px solid var(--fs-border);
  border-radius: 8px;
  background:
    linear-gradient(180deg, rgb(255 255 255 / 0.92), rgb(250 250 250 / 0.88)),
    repeating-linear-gradient(135deg, rgb(24 24 27 / 0.035) 0 1px, transparent 1px 12px);
  padding: 1rem;
  box-shadow: var(--fs-shadow);
}

.fs-brief__copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 0.25rem;
}

.fs-brief__eyebrow {
  color: var(--fs-text-muted);
  font-size: 0.75rem;
  font-weight: 600;
  line-height: 1rem;
}

.fs-brief__heading {
  display: flex;
  min-width: 0;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem;
}

.fs-brief__title {
  min-width: 0;
  overflow: hidden;
  color: var(--fs-text);
  font-size: 1.125rem;
  font-weight: 650;
  line-height: 1.5rem;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fs-brief__meta {
  color: var(--fs-text-muted);
  font-size: 0.8125rem;
  line-height: 1.125rem;
}

.fs-brief__error {
  color: #b91c1c;
  font-size: 0.75rem;
  line-height: 1.125rem;
  word-break: break-all;
}

/* ── 状态徽章：语义色按官方 Alert 约定（{c}-50 底 / {c}-300 边 / {c}-700 字） ── */
.fs-badge {
  display: inline-flex;
  align-items: center;
  gap: 0.3125rem;
  border: 1px solid transparent;
  border-radius: 999px;
  padding: 0.125rem 0.5625rem;
  font-size: 0.6875rem;
  font-weight: 500;
  line-height: 1rem;
  white-space: nowrap;
}

.fs-badge__dot {
  width: 0.375rem;
  height: 0.375rem;
  border-radius: 50%;
  background: currentColor;
}

.fs-badge.is-success {
  border-color: #86efac;
  background: #f0fdf4;
  color: #15803d;
}

.fs-badge.is-error {
  border-color: #fca5a5;
  background: #fef2f2;
  color: #b91c1c;
}

.fs-badge.is-warning {
  border-color: #fdba74;
  background: #fff7ed;
  color: #c2410c;
}

.fs-badge.is-muted {
  border-color: #e4e4e7;
  background: #fafafa;
  color: var(--fs-text-muted);
}

/* ── 工具栏：sticky + 毛玻璃，对齐链接订阅页 feed-toolbar ── */
.fs-toolbar {
  position: sticky;
  top: 0.75rem;
  z-index: 10;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  margin: 0.75rem 0;
  border: 1px solid var(--fs-border);
  border-radius: 8px;
  background: rgb(255 255 255 / 0.9);
  padding: 0.5rem 0.625rem;
  box-shadow: var(--fs-shadow);
  backdrop-filter: blur(14px);
}

/* Tabs：对齐链接订阅页的分段筛选，选中项为深色块 */
.fs-tabs {
  display: inline-flex;
  flex: none;
  gap: 0.25rem;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fafafa;
  padding: 0.25rem;
}

.fs-tab {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 3.5rem;
  height: 2rem;
  border: none;
  border-radius: 6px;
  background: none;
  padding: 0 0.75rem;
  font-size: 0.875rem;
  line-height: 1.25rem;
  color: #52525b;
  cursor: pointer;
  transition:
    background-color 0.18s,
    box-shadow 0.18s,
    color 0.18s;
}

.fs-tab:hover {
  color: #18181b;
}

.fs-tab.is-active {
  background: #18181b;
  color: #fafafa;
  box-shadow: 0 1px 2px rgb(15 23 42 / 0.16);
}

.fs-tab.is-active:hover {
  color: #fafafa;
}

.fs-toolbar__actions {
  display: flex;
  min-width: 0;
  flex: none;
  flex-wrap: wrap;
  justify-content: flex-end;
}

/* ── 条目流：外框交给 VCard，这里只负责行列表本身 ── */
.fs-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.fs-item {
  position: relative;
  display: flex;
  gap: 0.75rem;
  border-bottom: 1px solid #f4f4f5;
  padding: 1rem;
  transition: background-color 150ms cubic-bezier(0.4, 0, 0.2, 1);
}

.fs-item:last-child {
  border-bottom: none;
}

.fs-item:hover {
  background: #fafafa;
}

/* 圆角矩形对齐前台头像（42px、圆角 6px），不再用正圆 */
.fs-item__avatar {
  width: 2.625rem;
  height: 2.625rem;
  flex: none;
  border-radius: 6px;
  border: 1px solid var(--fs-border);
  background: #f0f0f0;
  object-fit: cover;
}

.fs-item__avatar--placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f0fdf9;
  color: #0d9488;
  font-weight: 600;
}

.fs-item__body {
  flex: 1;
  min-width: 0;
}

.fs-item__head {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.fs-state {
  width: 0.5rem;
  height: 0.5rem;
  flex: none;
  border-radius: 50%;
}

.fs-state--unread {
  background: #22c55e;
}

.fs-state--deleted {
  background: #ef4444;
}

.fs-item__check {
  display: inline-flex;
  align-items: center;
  flex: none;
}

.fs-batch {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  border-bottom: 1px solid #f4f4f5;
  padding: 0.625rem 1rem;
  font-size: 0.8125rem;
}

.fs-batch__all {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  cursor: pointer;
}

.fs-batch__count {
  color: var(--fs-text-muted);
}

.fs-item__author {
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--fs-text);
}

.fs-item__time {
  font-size: 0.75rem;
  color: var(--fs-text-muted);
  font-variant-numeric: tabular-nums;
}

.fs-item__title {
  margin-top: 0.25rem;
  font-size: 0.875rem;
  font-weight: 500;
  color: var(--fs-text);
}

.fs-item__text {
  margin: 0.25rem 0 0;
  display: -webkit-box;
  overflow: hidden;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  font-size: 0.8125rem;
  line-height: 1.55;
  color: var(--fs-text-secondary);
}

.fs-item__images {
  display: flex;
  flex-wrap: wrap;
  gap: 0.375rem;
  margin-top: 0.625rem;
}

.fs-item__images img {
  width: 3.75rem;
  height: 3.75rem;
  border-radius: 6px;
  border: 1px solid var(--fs-border);
  object-fit: cover;
}

/* 行操作：触屏常显；精细指针设备上 hover/focus 浮现，避免点不到 */
/* 组件 sm 图标槽只有 0.75rem，官方订阅列表的操作图标是 1rem */
.fs-item__actions :deep(.btn-icon) {
  width: 1rem;
  height: 1rem;
  margin-right: 0;
  color: #52525b;
}

/* 查看原文是原生 a（VButton 不支持渲染成链接），尺寸对齐 ghost sm 图标按钮 */
.fs-item__origin {
  display: inline-flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 1.75rem;
  height: 1.75rem;
  border-radius: var(--borderRadius-base, 4px);
  color: #52525b;
  text-decoration: none;
  transition: background-color 0.15s cubic-bezier(0.4, 0, 0.2, 1);
}

.fs-item__origin:hover {
  background-color: #f3f4f6;
}

.fs-item__origin:active {
  background-color: #e5e7eb;
}

.fs-item__origin svg {
  width: 1rem;
  height: 1rem;
}

.fs-item__actions {
  display: flex;
  flex: none;
  flex-wrap: wrap;
  justify-content: flex-end;
  align-self: flex-start;
  gap: 0.25rem;
  opacity: 1;
  transition: opacity 150ms cubic-bezier(0.4, 0, 0.2, 1);
}

@media (hover: hover) and (pointer: fine) {
  .fs-item__actions {
    opacity: 0;
  }

  .fs-item:hover .fs-item__actions,
  .fs-item:focus-within .fs-item__actions {
    opacity: 1;
  }
}

</style>
