export interface PartitionInfo {
    partition: number;
    leader: number;
    replicas: number[];
    online_replicas: number[];
    offline_replicas: number[];
    observers: number[];
    online_observers: number[];
    offline_observers: number[];
    in_sync_replicas: number[];
}

export interface TopicInfo {
    [topicName: string]: PartitionInfo[];
}

export interface TopicHealth {
    totalReplicas: number;
    onlineReplicas: number;
    offlineReplicas: number;
    totalObservers: number;
    onlineObservers: number;
    offlineObservers: number;
    inSyncReplicas: number;
}