import React, { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
    Container,
    Typography,
    Paper,
    Button,
    Box,
    Table,
    TableBody,
    TableCell,
    TableContainer,
    TableHead,
    TableRow,
    Chip,
    Tooltip,
    Alert
} from '@mui/material';
import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import axios from 'axios';
import { PartitionInfo, BrokerMap, BrokerInfo } from '../types';

const API_BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:9401';

interface BrokerStatus {
    type: 'none' | 'leader' | 'in-sync' | 'out-of-sync' | 'observer' | 'offline';
    isOnline: boolean;
}

interface RackGroup {
    rack: string;
    brokers: BrokerInfo[];
}

const TopicDetail: React.FC = () => {
    const { topicName } = useParams<{ topicName: string }>();
    const navigate = useNavigate();
    const [partitions, setPartitions] = useState<PartitionInfo[]>([]);
    const [brokers, setBrokers] = useState<BrokerMap>({});
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        const fetchData = async () => {
            try {
                const [topicResponse, brokerResponse] = await Promise.all([
                    axios.get<PartitionInfo[]>(`${API_BASE_URL}/topics/${topicName}`),
                    axios.get<BrokerMap>(`${API_BASE_URL}/brokers`)
                ]);
                
                setPartitions(topicResponse.data.sort((a, b) => a.partition - b.partition));
                setBrokers(brokerResponse.data);
                setError(null);
            } catch (err) {
                setError('Failed to fetch data');
                console.error(err);
            }
        };

        fetchData();
        const interval = setInterval(fetchData, 15000);
        return () => clearInterval(interval);
    }, [topicName]);

    const getBrokerStatus = (partition: PartitionInfo, brokerId: number): BrokerStatus => {
        if (partition.leader === brokerId) {
            return { 
                type: 'leader',
                isOnline: !partition.offline_replicas.includes(brokerId)
            };
        }
        if (partition.in_sync_replicas.includes(brokerId)) {
            return { 
                type: 'in-sync',
                isOnline: !partition.offline_replicas.includes(brokerId)
            };
        }
        if (partition.out_of_sync_replicas.includes(brokerId)) {
            return { 
                type: 'out-of-sync',
                isOnline: !partition.offline_replicas.includes(brokerId)
            };
        }
        if (partition.observers.includes(brokerId)) {
            return { 
                type: 'observer',
                isOnline: !partition.offline_replicas.includes(brokerId)
            };
        }
        if (partition.offline_replicas.includes(brokerId)) {
            return { 
                type: 'none',
                isOnline: false
            };
        }
        return { type: 'none', isOnline: true };
    };

    const getStatusChip = (status: BrokerStatus) => {
        const getChipProps = () => {
            if (!status.isOnline) {
                return {
                    label: 'Offline',
                    color: 'error' as const,
                    variant: 'outlined' as const
                };
            }

            switch (status.type) {
                case 'leader':
                    return {
                        label: 'Leader',
                        color: 'success' as const,
                        variant: 'filled' as const
                    };
                case 'in-sync':
                    return {
                        label: 'In-Sync',
                        color: 'info' as const,
                        variant: 'filled' as const
                    };
                case 'out-of-sync':
                    return {
                        label: 'Out of Sync',
                        color: 'warning' as const,
                        variant: 'filled' as const
                    };
                case 'observer':
                    return {
                        label: 'Observer',
                        color: 'secondary' as const,
                        variant: 'filled' as const
                    };
                default:
                    return {
                        label: '-',
                        color: 'default' as const,
                        variant: 'outlined' as const
                    };
            }
        };

        const chipProps = getChipProps();
        return (
            <Tooltip title={chipProps.label}>
                <Chip
                    {...chipProps}
                    size="small"
                    sx={{
                        width: '100%',
                        '& .MuiChip-label': {
                            overflow: 'hidden',
                            textOverflow: 'ellipsis'
                        }
                    }}
                />
            </Tooltip>
        );
    };

    // Group brokers by rack
    const brokerValues = Object.values(brokers);
    const rackGroups = brokerValues.reduce<RackGroup[]>((groups, broker) => {
        const existingGroup = groups.find(g => g.rack === broker.rack);
        if (existingGroup) {
            existingGroup.brokers.push(broker);
        } else {
            groups.push({ rack: broker.rack, brokers: [broker] });
        }
        return groups;
    }, []).sort((a, b) => a.rack.localeCompare(b.rack));

    // Sort brokers within each rack
    rackGroups.forEach(group => {
        group.brokers.sort((a, b) => a.id - b.id);
    });

    // Add function to calculate summary
    const calculateSummary = () => {
        const summary = {
            totalReplicas: 0,
            offlineReplicas: 0,
            totalPartitions: partitions.length
        };

        partitions.forEach(partition => {
            summary.totalReplicas += partition.replicas.length;
            summary.offlineReplicas += partition.offline_replicas.length;
        });

        return summary;
    };

    const summary = calculateSummary();

    return (
        <Container maxWidth="lg" sx={{ mt: 4 }}>
            <Box sx={{ display: 'flex', alignItems: 'center', mb: 3 }}>
                <Button
                    startIcon={<ArrowBackIcon />}
                    onClick={() => navigate('/')}
                    sx={{ mr: 2 }}
                >
                    Back
                </Button>
                <Typography variant="h4" component="h1">
                    Topic: {topicName}
                </Typography>
            </Box>

            {error && (
                <Typography color="error" variant="h6" gutterBottom>
                    {error}
                </Typography>
            )}

            <Box sx={{ mb: 3 }}>
                <Typography variant="h6" gutterBottom>
                    Summary
                </Typography>
                <Typography variant="body1">
                    Total Partitions: {summary.totalPartitions}
                </Typography>
                <Typography variant="body1">
                    Total Replicas: {summary.totalReplicas}
                </Typography>
                {summary.offlineReplicas > 0 && (
                    <Alert severity="error" sx={{ mt: 1 }}>
                        {summary.offlineReplicas} replica(s) are offline
                    </Alert>
                )}
            </Box>

            <TableContainer component={Paper}>
                <Table size="small">
                    <TableHead>
                        <TableRow>
                            <TableCell rowSpan={2}>Partition</TableCell>
                            {rackGroups.map((rackGroup) => (
                                <TableCell 
                                    key={rackGroup.rack}
                                    align="center"
                                    colSpan={rackGroup.brokers.length}
                                    sx={{ 
                                        borderLeft: '1px solid rgba(224, 224, 224, 1)',
                                        backgroundColor: 'rgba(0, 0, 0, 0.02)'
                                    }}
                                >
                                    Rack: {rackGroup.rack}
                                </TableCell>
                            ))}
                        </TableRow>
                        <TableRow>
                            {rackGroups.map((rackGroup) => (
                                rackGroup.brokers.map((broker) => (
                                    <TableCell
                                        key={broker.id}
                                        align="center"
                                        sx={{ 
                                            borderLeft: '1px solid rgba(224, 224, 224, 1)',
                                            minWidth: '100px'
                                        }}
                                    >
                                        <Tooltip title={`${broker.host}:${broker.port}`}>
                                            <Typography variant="body2">
                                                Broker {broker.id}
                                            </Typography>
                                        </Tooltip>
                                    </TableCell>
                                ))
                            ))}
                        </TableRow>
                    </TableHead>
                    <TableBody>
                        {partitions.map((partition) => (
                            <TableRow key={partition.partition}>
                                <TableCell>{partition.partition}</TableCell>
                                {rackGroups.map((rackGroup) => (
                                    rackGroup.brokers.map((broker) => (
                                        <TableCell
                                            key={broker.id}
                                            align="center"
                                            sx={{ borderLeft: '1px solid rgba(224, 224, 224, 1)' }}
                                        >
                                            {getStatusChip(getBrokerStatus(partition, broker.id))}
                                        </TableCell>
                                    ))
                                ))}
                            </TableRow>
                        ))}
                    </TableBody>
                </Table>
            </TableContainer>
        </Container>
    );
};

export default TopicDetail;