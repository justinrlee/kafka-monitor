export interface PartitionInfo {
    partition: number;
    leader: number;
    replicas: number[];
    in_sync_replicas: number[];
    out_of_sync_replicas: number[];
    observers: number[];
    offline_replicas: number[];
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

export interface BrokerInfo {
    id: number;
    rack: string;
    host: string;
    port: number;
}

export interface BrokerMap {
    [brokerId: string]: BrokerInfo;
}