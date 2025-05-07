import React, { useEffect, useState } from 'react';
import { Container, Typography, Grid } from '@mui/material';
import axios from 'axios';
import TopicHealthCard from './TopicHealthCard';
import { TopicInfo, TopicHealth, PartitionInfo } from '../types';

// Use relative path since we're using Vite's proxy
const API_BASE_URL = '';

const calculateTopicHealth = (partitions: PartitionInfo[]): TopicHealth => {
    const health: TopicHealth = {
        totalReplicas: 0,
        onlineReplicas: 0,
        offlineReplicas: 0,
        totalObservers: 0,
        onlineObservers: 0,
        offlineObservers: 0,
        inSyncReplicas: 0,
        promotedObservers: 0,
        partitionCount: partitions.length
    };

    partitions.forEach(partition => {
        // Calculate regular replica metrics
        const regularReplicas = partition.replicas.filter(id => !partition.observers.includes(id));
        health.totalReplicas += regularReplicas.length;
        health.offlineReplicas += partition.offline_replicas.filter(id => !partition.observers.includes(id)).length;
        health.onlineReplicas = health.totalReplicas - health.offlineReplicas;
        health.inSyncReplicas += partition.in_sync_replicas.filter(id => !partition.observers.includes(id)).length;

        // Calculate observer metrics
        health.totalObservers += partition.observers.length;
        health.offlineObservers += partition.offline_replicas.filter(id => partition.observers.includes(id)).length;
        health.onlineObservers = health.totalObservers - health.offlineObservers;
        health.promotedObservers += partition.promoted_observers.length;
    });

    return health;
};

const TopicsList = () => {
    const [topicsHealth, setTopicsHealth] = useState<{ [key: string]: TopicHealth }>({});
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        const fetchTopics = async () => {
            try {
                const response = await axios.get<TopicInfo>(`${API_BASE_URL}/topics`);
                const health: { [key: string]: TopicHealth } = {};
                
                Object.entries(response.data).forEach(([topicName, partitions]) => {
                    health[topicName] = calculateTopicHealth(partitions as PartitionInfo[]);
                });
                
                setTopicsHealth(health);
                setError(null);
            } catch (err) {
                setError('Failed to fetch topic information');
                console.error(err);
            }
        };

        fetchTopics();
        const interval = setInterval(fetchTopics, 15000);

        return () => clearInterval(interval);
    }, []);

    return (
        <Container maxWidth="lg" sx={{ mt: 4 }}>
            <Typography variant="h4" component="h1" gutterBottom>
                Kafka Topics Health
            </Typography>
            
            {error && (
                <Typography color="error" variant="h6" gutterBottom>
                    {error}
                </Typography>
            )}

            <Grid container spacing={3}>
                {Object.entries(topicsHealth).map(([topicName, health]) => (
                    <Grid item xs={12} sm={6} md={4} key={topicName}>
                        <TopicHealthCard 
                            topicName={topicName} 
                            health={health}
                        />
                    </Grid>
                ))}
            </Grid>
        </Container>
    );
};

export default TopicsList;
