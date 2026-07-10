<script lang="ts" setup>
import { axiosInstance } from '@halo-dev/api-client'
import { Dialog, Toast, VButton, VEmpty, VLoading, VPageHeader, VSpace } from '@halo-dev/components'
import { computed, onMounted, ref } from 'vue'
import RiFireLine from '~icons/ri/fire-line'
import RiPauseCircleLine from '~icons/ri/pause-circle-line'
import RiPlayCircleLine from '~icons/ri/play-circle-line'
import RiRefreshLine from '~icons/ri/refresh-line'
import RiDeleteBinLine from '~icons/ri/delete-bin-line'
import RiExternalLinkLine from '~icons/ri/external-link-line'
import {
  PAUSED_ANNO,
  RSS_URL_ANNO,
  type FeedStatus,
  type FriendMoment,
  type Link,
  type ListResult,
  type SyncResponse,
} from '@/types'

const PAGE_SIZE = 20

const links = ref<Link[]>([])
const statuses = ref<Map<string, FeedStatus>>(new Map())
const moments = ref<FriendMoment[]>([])
const total = ref(0)
const page = ref(1)
const selectedLinkName = ref<string>('')

const loadingList = ref(false)
const loadingLinks = ref(false)
const syncingLink = ref(false)
const syncingAll = ref(false)
const togglingPause = ref(false)
const clearing = ref(false)

const totalPages = computed(() => Math.max(Math.ceil(total.value / PAGE_SIZE), 1))

const selectedLink = computed(() =>
  links.value.find((link) => link.metadata.name === selectedLinkName.value),
)

const selectedStatus = computed(() =>
  selectedLinkName.value ? statuses.value.get(selectedLinkName.value) : undefined,
)

const selectedTitle = computed(() => {
  if (!selectedLink.value) return '全部动态'
  return displayNameOf(selectedLink.value)
})

const selectedMeta = computed(() => {
  if (!selectedLink.value) {
    return `${links.value.length} 个订阅 / ${total.value} 条动态`
  }
  const itemCount = selectedStatus.value?.spec?.itemCount ?? total.value
  return `${itemCount} 条动态`
})

interface StatusMeta {
  tone: 'success' | 'error' | 'muted' | 'warning'
  label: string
  description?: string
}

const selectedStatusMeta = computed<StatusMeta>(() => {
  if (!selectedLink.value) {
    const failed = links.value.filter((link) => {
      const s = statuses.value.get(link.metadata.name)
      return s?.spec?.lastError
    }).length
    if (!links.value.length) return { tone: 'muted', label: '暂无订阅' }
    if (failed) return { tone: 'error', label: `${failed} 个订阅同步失败` }
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
      label: `同步失败（连续 ${spec.failureCount ?? 1} 次）`,
      description: spec.lastError,
    }
  }
  return { tone: 'success', label: '正常', description: `上次同步：${formatDate(spec.lastSuccessAt)}` }
}

function displayNameOf(link: Link) {
  return link.spec?.displayName || link.metadata.name
}

function isPaused(link: Link) {
  return link.metadata.annotations?.[PAUSED_ANNO] === 'true'
}

function statusDotClass(link: Link) {
  if (isPaused(link)) return 'is-warning'
  const spec = statuses.value.get(link.metadata.name)?.spec
  if (!spec?.lastFetchedAt) return 'is-muted'
  return spec.lastError ? 'is-error' : 'is-success'
}

function itemCountOf(link: Link) {
  return statuses.value.get(link.metadata.name)?.spec?.itemCount ?? 0
}

function formatDate(value?: string | null) {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '-'
  const pad = (n: number) => `${n}`.padStart(2, '0')
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}`
  )
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
  } finally {
    loadingLinks.value = false
  }
}

async function fetchMoments() {
  loadingList.value = true
  try {
    const params: Record<string, unknown> = {
      page: page.value,
      size: PAGE_SIZE,
      sort: 'spec.pubDate,desc',
    }
    if (selectedLinkName.value) {
      params.fieldSelector = `spec.linkName=${selectedLinkName.value}`
    }
    const { data } = await axiosInstance.get<ListResult<FriendMoment>>(
      '/apis/fireside.timxs.com/v1alpha1/friendmoments',
      { params },
    )
    moments.value = data.items
    total.value = data.total
  } finally {
    loadingList.value = false
  }
}

async function reload() {
  await Promise.all([fetchLinks(), fetchMoments()])
}

function selectLink(name: string) {
  if (selectedLinkName.value === name) return
  selectedLinkName.value = name
  page.value = 1
  fetchMoments()
}

function prevPage() {
  if (page.value <= 1) return
  page.value -= 1
  fetchMoments()
}

function nextPage() {
  if (page.value >= totalPages.value) return
  page.value += 1
  fetchMoments()
}

async function sync(name: string, all: boolean) {
  const loading = all ? syncingAll : syncingLink
  loading.value = true
  try {
    const { data } = await axiosInstance.post<SyncResponse>(
      `/apis/console.api.fireside.timxs.com/v1alpha1/links/${name}/sync`,
    )
    showSyncSummary(data, all ? '全部订阅' : '当前订阅')
    await reload()
  } catch (e) {
    Toast.error(errorMessage(e, '同步请求失败'))
  } finally {
    loading.value = false
  }
}

function showSyncSummary(summary: SyncResponse, label: string) {
  if (!summary.total) {
    Toast.info('没有可同步的订阅')
    return
  }
  const text = `新增 ${summary.createdCount} 条`
  if (!summary.failureCount) {
    Toast.success(`${label}同步完成：成功 ${summary.successCount} 个，${text}`)
    return
  }
  const failText = summary.results
    .filter((r) => r.error)
    .map((r) => `${r.displayName}：${r.error}`)
    .join('；')
  if (summary.failureCount === summary.total) {
    Toast.error(`${label}同步失败：${failText}`)
    return
  }
  Toast.warning(`${label}同步完成：成功 ${summary.successCount} 个，失败 ${summary.failureCount} 个（${failText}），${text}`)
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
    annotations[PAUSED_ANNO] = pausing ? 'true' : 'false'
    latest.metadata.annotations = annotations
    await axiosInstance.put(`/apis/core.halo.run/v1alpha1/links/${link.metadata.name}`, latest)
    Toast.success(pausing ? '已暂停该订阅（数据保留）' : '已恢复该订阅')
    await fetchLinks()
  } catch (e) {
    Toast.error(errorMessage(e, '操作失败'))
  } finally {
    togglingPause.value = false
  }
}

function removeMoment(moment: FriendMoment) {
  Dialog.warning({
    title: '删除动态',
    description: '确认删除这条友链动态吗？下次同步如果源里仍存在该条目会重新抓取。',
    confirmType: 'danger',
    onConfirm: async () => {
      await axiosInstance.delete(
        `/apis/fireside.timxs.com/v1alpha1/friendmoments/${moment.metadata.name}`,
      )
      Toast.success('已删除')
      await reload()
    },
  })
}

function clearMoments() {
  const scope = selectedLink.value ? `「${selectedTitle.value}」` : '全部友链'
  Dialog.warning({
    title: '清空友链动态',
    description: `确认删除${scope}的所有已抓取动态吗？订阅本身不受影响，下次同步会重新抓取最新内容。`,
    confirmType: 'danger',
    onConfirm: async () => {
      clearing.value = true
      try {
        const params: Record<string, unknown> = { size: 1000 }
        if (selectedLinkName.value) {
          params.fieldSelector = `spec.linkName=${selectedLinkName.value}`
        }
        const { data } = await axiosInstance.get<ListResult<FriendMoment>>(
          '/apis/fireside.timxs.com/v1alpha1/friendmoments',
          { params },
        )
        for (const item of data.items) {
          await axiosInstance.delete(
            `/apis/fireside.timxs.com/v1alpha1/friendmoments/${item.metadata.name}`,
          )
        }
        Toast.success(`已清空 ${data.items.length} 条动态`)
        page.value = 1
        await reload()
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
  <VPageHeader title="围炉">
    <template #icon>
      <RiFireLine />
    </template>
    <template #actions>
      <VSpace>
        <VButton
          size="sm"
          :disabled="!links.length || syncingLink || syncingAll"
          :loading="syncingAll"
          @click="sync('-', true)"
        >
          <template #icon>
            <RiRefreshLine />
          </template>
          同步全部
        </VButton>
      </VSpace>
    </template>
  </VPageHeader>

  <div class="fireside-page">
    <div class="fireside-workspace">
      <!-- 左侧：订阅列表 -->
      <aside class="fireside-sidebar">
        <div
          class="fireside-source"
          :class="{ 'is-active': !selectedLinkName }"
          @click="selectLink('')"
        >
          <span class="fireside-source__name">全部动态</span>
          <span class="fireside-source__count">{{ total }}</span>
        </div>
        <VLoading v-if="loadingLinks && !links.length" />
        <template v-else>
          <div
            v-for="link in links"
            :key="link.metadata.name"
            class="fireside-source"
            :class="{ 'is-active': selectedLinkName === link.metadata.name }"
            @click="selectLink(link.metadata.name)"
          >
            <span class="fireside-dot" :class="statusDotClass(link)"></span>
            <img v-if="link.spec?.logo" class="fireside-source__logo" :src="link.spec.logo" alt="" />
            <span class="fireside-source__name">
              {{ displayNameOf(link) }}
              <em v-if="isPaused(link)" class="fireside-source__paused">已暂停</em>
            </span>
            <span class="fireside-source__count">{{ itemCountOf(link) }}</span>
          </div>
          <div v-if="!links.length" class="fireside-sidebar__empty">
            还没有订阅：在「链接」中编辑友链，填写“动态 RSS 地址”即可
          </div>
        </template>
      </aside>

      <!-- 右侧：概览 + 工具栏 + 条目流 -->
      <div class="fireside-main">
        <div class="fireside-brief">
          <div class="fireside-brief__copy">
            <div class="fireside-brief__heading">
              <strong>{{ selectedTitle }}</strong>
              <span class="fireside-badge" :class="`is-${selectedStatusMeta.tone}`">
                {{ selectedStatusMeta.label }}
              </span>
            </div>
            <span class="fireside-brief__meta">{{ selectedMeta }}</span>
            <span v-if="selectedStatusMeta.description" class="fireside-brief__desc">
              {{ selectedStatusMeta.description }}
            </span>
          </div>
          <VSpace>
            <VButton
              v-if="selectedLink"
              size="sm"
              :loading="syncingLink"
              :disabled="syncingAll || isPaused(selectedLink)"
              @click="sync(selectedLinkName, false)"
            >
              <template #icon>
                <RiRefreshLine />
              </template>
              同步当前
            </VButton>
            <VButton
              v-if="selectedLink"
              size="sm"
              :loading="togglingPause"
              @click="togglePause"
            >
              <template #icon>
                <RiPlayCircleLine v-if="isPaused(selectedLink)" />
                <RiPauseCircleLine v-else />
              </template>
              {{ isPaused(selectedLink) ? '恢复同步' : '暂停同步' }}
            </VButton>
            <VButton
              size="sm"
              type="danger"
              ghost
              :loading="clearing"
              :disabled="!total"
              @click="clearMoments"
            >
              <template #icon>
                <RiDeleteBinLine />
              </template>
              清空{{ selectedLink ? '当前' : '全部' }}
            </VButton>
          </VSpace>
        </div>

        <VLoading v-if="loadingList && !moments.length" />
        <VEmpty
          v-else-if="!moments.length"
          title="暂无友链动态"
          message="填写友链的动态 RSS 地址后，等待定时同步或点击同步按钮"
        >
          <template #actions>
            <VButton :loading="syncingAll" :disabled="!links.length" @click="sync('-', true)">
              立即同步
            </VButton>
          </template>
        </VEmpty>
        <ul v-else class="fireside-list">
          <li v-for="moment in moments" :key="moment.metadata.name" class="fireside-item">
            <img
              v-if="moment.spec.logo"
              class="fireside-item__avatar"
              :src="moment.spec.logo"
              alt=""
              referrerpolicy="no-referrer"
            />
            <div v-else class="fireside-item__avatar fireside-item__avatar--placeholder">
              {{ (moment.spec.author || '?').slice(0, 1) }}
            </div>
            <div class="fireside-item__body">
              <div class="fireside-item__head">
                <span class="fireside-item__author">{{ moment.spec.author }}</span>
                <span class="fireside-item__time">{{ formatDate(moment.spec.pubDate) }}</span>
              </div>
              <div v-if="moment.spec.title" class="fireside-item__title">
                {{ moment.spec.title }}
              </div>
              <p class="fireside-item__text">{{ moment.spec.contentText }}</p>
              <div v-if="moment.spec.images?.length" class="fireside-item__images">
                <img
                  v-for="img in moment.spec.images.slice(0, 9)"
                  :key="img"
                  :src="img"
                  alt=""
                  referrerpolicy="no-referrer"
                  loading="lazy"
                />
              </div>
            </div>
            <div class="fireside-item__actions">
              <a :href="moment.spec.postLink" target="_blank" rel="noreferrer" title="查看原文">
                <RiExternalLinkLine />
              </a>
              <button type="button" title="删除" @click="removeMoment(moment)">
                <RiDeleteBinLine />
              </button>
            </div>
          </li>
        </ul>

        <div v-if="total > PAGE_SIZE" class="fireside-pagination">
          <VButton size="sm" :disabled="page <= 1 || loadingList" @click="prevPage">上一页</VButton>
          <span>第 {{ page }} / {{ totalPages }} 页，共 {{ total }} 条</span>
          <VButton size="sm" :disabled="page >= totalPages || loadingList" @click="nextPage">
            下一页
          </VButton>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.fireside-page {
  padding: 1rem;
}

.fireside-workspace {
  display: flex;
  gap: 1rem;
  align-items: flex-start;
  flex-direction: column;
}

@media (min-width: 1024px) {
  .fireside-workspace {
    flex-direction: row;
  }

  .fireside-sidebar {
    width: 16rem;
    flex: none;
  }
}

.fireside-sidebar {
  width: 100%;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
  padding: 0.5rem;
  display: flex;
  flex-direction: column;
  gap: 0.125rem;
}

.fireside-source {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 0.625rem;
  border-radius: 6px;
  cursor: pointer;
  font-size: 0.875rem;
  color: #18181b;
}

.fireside-source:hover {
  background: #f4f4f5;
}

.fireside-source.is-active {
  background: #eef2ff;
  font-weight: 600;
}

.fireside-source__logo {
  width: 1.25rem;
  height: 1.25rem;
  border-radius: 50%;
  object-fit: cover;
  flex: none;
}

.fireside-source__name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fireside-source__paused {
  font-style: normal;
  font-size: 0.6875rem;
  color: #b45309;
  margin-left: 0.25rem;
}

.fireside-source__count {
  flex: none;
  font-size: 0.75rem;
  color: #71717a;
}

.fireside-sidebar__empty {
  padding: 0.75rem 0.625rem;
  font-size: 0.8125rem;
  color: #71717a;
  line-height: 1.4;
}

.fireside-dot {
  width: 0.5rem;
  height: 0.5rem;
  border-radius: 50%;
  flex: none;
  background: #d4d4d8;
}

.fireside-dot.is-success {
  background: #22c55e;
}

.fireside-dot.is-error {
  background: #ef4444;
}

.fireside-dot.is-warning {
  background: #f59e0b;
}

.fireside-dot.is-muted {
  background: #d4d4d8;
}

.fireside-main {
  flex: 1;
  min-width: 0;
  width: 100%;
}

.fireside-brief {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 1rem;
  flex-wrap: wrap;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
  padding: 1rem;
  margin-bottom: 0.75rem;
}

.fireside-brief__copy {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  min-width: 0;
}

.fireside-brief__heading {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex-wrap: wrap;
  font-size: 1.125rem;
}

.fireside-brief__meta {
  font-size: 0.8125rem;
  color: #71717a;
}

.fireside-brief__desc {
  font-size: 0.75rem;
  color: #a1a1aa;
  word-break: break-all;
}

.fireside-badge {
  font-size: 0.6875rem;
  border-radius: 999px;
  padding: 0.125rem 0.5rem;
  font-weight: 500;
}

.fireside-badge.is-success {
  background: #dcfce7;
  color: #15803d;
}

.fireside-badge.is-error {
  background: #fee2e2;
  color: #b91c1c;
}

.fireside-badge.is-warning {
  background: #fef3c7;
  color: #b45309;
}

.fireside-badge.is-muted {
  background: #f4f4f5;
  color: #71717a;
}

.fireside-list {
  list-style: none;
  margin: 0;
  padding: 0;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
  overflow: hidden;
}

.fireside-item {
  display: flex;
  gap: 0.75rem;
  padding: 0.875rem 1rem;
  border-bottom: 1px solid #f4f4f5;
}

.fireside-item:last-child {
  border-bottom: none;
}

.fireside-item__avatar {
  width: 2.25rem;
  height: 2.25rem;
  border-radius: 50%;
  object-fit: cover;
  flex: none;
}

.fireside-item__avatar--placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  background: #eef2ff;
  color: #4f46e5;
  font-weight: 600;
}

.fireside-item__body {
  flex: 1;
  min-width: 0;
}

.fireside-item__head {
  display: flex;
  align-items: baseline;
  gap: 0.5rem;
}

.fireside-item__author {
  font-weight: 600;
  font-size: 0.875rem;
}

.fireside-item__time {
  font-size: 0.75rem;
  color: #a1a1aa;
}

.fireside-item__title {
  margin-top: 0.25rem;
  font-size: 0.875rem;
  font-weight: 500;
}

.fireside-item__text {
  margin: 0.25rem 0 0;
  font-size: 0.8125rem;
  color: #52525b;
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.fireside-item__images {
  display: flex;
  flex-wrap: wrap;
  gap: 0.375rem;
  margin-top: 0.5rem;
}

.fireside-item__images img {
  width: 4.5rem;
  height: 4.5rem;
  border-radius: 6px;
  object-fit: cover;
}

.fireside-item__actions {
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
  flex: none;
}

.fireside-item__actions a,
.fireside-item__actions button {
  display: inline-flex;
  border: none;
  background: none;
  padding: 0.25rem;
  border-radius: 4px;
  cursor: pointer;
  color: #71717a;
}

.fireside-item__actions a:hover,
.fireside-item__actions button:hover {
  background: #f4f4f5;
  color: #18181b;
}

.fireside-pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.75rem;
  margin-top: 0.75rem;
  font-size: 0.8125rem;
  color: #71717a;
}
</style>
