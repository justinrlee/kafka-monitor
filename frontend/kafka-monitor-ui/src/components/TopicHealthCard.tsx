import React from 'react';
import { Card, CardContent, Typography, LinearProgress, Box, CardActionArea } from '@mui/material';
import { useNavigate } from 'react-router-dom';
import { TopicHealth } from '../types';

interface TopicHealthCardProps {
    topicName: string;
    health: TopicHealth;
}

const TopicHealthCard: React.FC<TopicHealthCardProps> = ({ topicName, health }) => {
    const navigate = useNavigate();
    const replicaHealth = (health.onlineReplicas / health.totalReplicas) * 100;
    const observerHealth = health.totalObservers > 0
        ? (health.onlineObservers / health.totalObservers) * 100
        : 100;

    return (
        <Card sx={{ minWidth: 275, m: 1 }}>
            <CardActionArea onClick={() => navigate(`/topic/${topicName}`)}>
                <CardContent>
                    <Typography variant="h5" component="div" gutterBottom>
                        {topicName}
                    </Typography>

                    <Box sx={{ mb: 2 }}>
                        <Typography variant="subtitle1" color="text.secondary">
                            Replicas: {health.onlineReplicas}/{health.totalReplicas} online
                        </Typography>
                        <LinearProgress
                            variant="determinate"
                            value={replicaHealth}
                            color={replicaHealth === 100 ? "success" : "warning"}
                            sx={{ height: 10, borderRadius: 5 }}
                        />
                    </Box>

                    {health.totalObservers > 0 && (
                        <Box sx={{ mb: 2 }}>
                            <Typography variant="subtitle1" color="text.secondary">
                                Observers: {health.onlineObservers}/{health.totalObservers} online
                            </Typography>
                            <LinearProgress
                                variant="determinate"
                                value={observerHealth}
                                color={observerHealth === 100 ? "success" : "warning"}
                                sx={{ height: 10, borderRadius: 5 }}
                            />
                        </Box>
                    )}

                    <Typography variant="body2" color="text.secondary">
                        In-sync replicas: {health.inSyncReplicas}
                    </Typography>
                    {health.offlineReplicas > 0 && (
                        <Typography variant="body2" color="error">
                            Offline replicas: {health.offlineReplicas}
                        </Typography>
                    )}
                    {health.offlineObservers > 0 && (
                        <Typography variant="body2" color="error">
                            Offline observers: {health.offlineObservers}
                        </Typography>
                    )}
                </CardContent>
            </CardActionArea>
        </Card>
    );
};

export default TopicHealthCard;