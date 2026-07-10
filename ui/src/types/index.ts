export interface Metadata {
  name: string
  annotations?: Record<string, string> | null
  creationTimestamp?: string | null
  version?: number | null
}

export interface Link {
  metadata: Metadata
  spec?: {
    displayName?: string
    url?: string
    logo?: string
    description?: string
  }
  [key: string]: unknown
}

export interface FeedStatus {
  metadata: Metadata
  spec?: {
    linkName?: string
    rssUrl?: string
    lastFetchedAt?: string | null
    lastSuccessAt?: string | null
    lastError?: string | null
    failureCount?: number | null
    latestPublishedAt?: string | null
    itemCount?: number | null
  }
}

export interface FriendMoment {
  metadata: Metadata
  spec: {
    linkName: string
    author?: string
    authorUrl?: string
    authorBio?: string
    logo?: string
    title?: string | null
    postLink: string
    contentHtml?: string
    contentText?: string
    images?: string[]
    pubDate?: string
  }
}

export interface ListResult<T> {
  items: T[]
  total: number
  page: number
  size: number
}

export interface LinkSyncResult {
  linkName: string
  displayName: string
  created: number
  trimmed: number
  error?: string | null
}

export interface SyncResponse {
  total: number
  successCount: number
  failureCount: number
  createdCount: number
  results: LinkSyncResult[]
}

export const RSS_URL_ANNO = 'fireside_rss_url'
export const PAUSED_ANNO = 'fireside_paused'
