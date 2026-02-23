import { createContext, useContext, useRef, type ReactNode } from 'react'
import { useStore } from 'zustand'
import { createAgentStore, type AgentState, type AgentStoreApi } from '../stores/create-agent-store'
import { createFlowStore, type FlowState, type FlowStoreApi } from '../stores/create-flow-store'

interface TabStores {
  agentStore: AgentStoreApi
  flowStore: FlowStoreApi
}

const TabStoreContext = createContext<TabStores | null>(null)

/**
 * Provides per-tab agent and flow stores via React context.
 * Stores are lazily created per tabId and cached in a ref-map.
 */
export function TabStoreProvider({ tabId, children }: { tabId: string; children: ReactNode }) {
  const storeMapRef = useRef<Map<string, TabStores>>(new Map())

  if (!storeMapRef.current.has(tabId)) {
    storeMapRef.current.set(tabId, {
      agentStore: createAgentStore(tabId),
      flowStore: createFlowStore(),
    })
  }

  const stores = storeMapRef.current.get(tabId)!

  return (
    <TabStoreContext.Provider value={stores}>
      {children}
    </TabStoreContext.Provider>
  )
}

/** Remove a tab's stores from the cache (call on tab close) */
export function useTabStoreCleanup() {
  const storeMapRef = useRef<Map<string, TabStores>>(new Map())
  return {
    remove: (tabId: string) => storeMapRef.current.delete(tabId),
  }
}

/** Hook to select state from the active tab's agent store */
export function useTabAgentStore<T>(selector: (state: AgentState) => T): T {
  const ctx = useContext(TabStoreContext)
  if (!ctx) throw new Error('useTabAgentStore must be used within TabStoreProvider')
  return useStore(ctx.agentStore, selector)
}

/** Hook to select state from the active tab's flow store */
export function useTabFlowStore<T>(selector: (state: FlowState) => T): T {
  const ctx = useContext(TabStoreContext)
  if (!ctx) throw new Error('useTabFlowStore must be used within TabStoreProvider')
  return useStore(ctx.flowStore, selector)
}

/** Hook to get raw store references for imperative access (e.g. getState()) */
export function useTabStores(): TabStores {
  const ctx = useContext(TabStoreContext)
  if (!ctx) throw new Error('useTabStores must be used within TabStoreProvider')
  return ctx
}
