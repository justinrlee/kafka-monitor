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
    type: 'none' | 'leader' | 'in-sync' | 'out-of-sync' | 'observer' | 'promoted-observer' | 'offline';
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
    const [isLoading, setIsLoading] = useState(true);

    useEffect(() => {
        const fetchData = async () => {
            try {
                setIsLoading(true);
                setError(null);

                // Create a timeout promise
                const timeoutPromise = new Promise((_, reject) => {
                    setTimeout(() => {
                        reject(new Error('timeout'));
                    }, 5000); // 5 second timeout
                });

                // Race between the actual requests and the timeout
                const [topicResponse, brokerResponse] = await Promise.race([
                    Promise.all([
                        axios.get<PartitionInfo[]>(`${API_BASE_URL}/topics/${topicName}/partitions`),
                        axios.get<BrokerMap>(`${API_BASE_URL}/brokers`)
                    ]),
                    timeoutPromise
                ]) as [any, any];
                
                setPartitions(topicResponse.data.sort((a, b) => a.partition - b.partition));
                setBrokers(brokerResponse.data);
            } catch (err) {
                console.error(err);
                if (err.message === 'timeout') {
                    setError('Request timed out. Please check if the API server is running and VITE_API_URL is set correctly.');
                } else if (axios.isAxiosError(err) && !err.response) {
                    setError('Unable to connect to API. Please check if VITE_API_URL is set correctly or if the API server is running.');
                } else {
                    setError('Failed to fetch data');
                }
            } finally {
                setIsLoading(false);
            }
        };

        fetchData();
        const interval = setInterval(fetchData, 15000);
        return () => clearInterval(interval);
    }, [topicName]);

    const getPartitionStatus = (partition: PartitionInfo, brokerId: number): BrokerStatus => {
        if (partition.leader === brokerId) {
            return { 
                type: 'leader',
                isOnline: !partition.offline_replicas.includes(brokerId)
            };
        }
        if (partition.promoted_observers.includes(brokerId)) {
            return { 
                type: 'promoted-observer',
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
                        variant: 'filled' as const,
                        sx: { bgcolor: 'success.light' }
                    };
                case 'in-sync':
                    return {
                        label: 'Follower',
                        color: 'info' as const,
                        variant: 'filled' as const,
                        sx: { bgcolor: 'success.main' }
                    };
                case 'out-of-sync':
                    return {
                        label: 'Out of Sync',
                        color: 'warning' as const,
                        variant: 'filled' as const
                    };
                case 'promoted-observer':
                    return {
                        label: 'Promoted',
                        color: 'warning' as const,
                        variant: 'filled' as const
                    };
                case 'observer':
                    return {
                        label: 'Observer',
                        color: 'info' as const,
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
                        },
                        ...chipProps.sx
                    }}
                />
            </Tooltip>
        );
    };

    // Get all broker IDs from both broker list and partition information
    const getAllBrokerIds = () => {
        const brokerIds = new Set<number>();
        
        // Add known brokers
        Object.values(brokers).forEach(broker => {
            brokerIds.add(broker.id);
        });

        // Add any brokers mentioned in partition information
        partitions.forEach(partition => {
            partition.replicas.forEach(id => brokerIds.add(id));
            partition.in_sync_replicas.forEach(id => brokerIds.add(id));
            partition.out_of_sync_replicas?.forEach(id => brokerIds.add(id));
            partition.observers.forEach(id => brokerIds.add(id));
            partition.offline_replicas.forEach(id => brokerIds.add(id));
        });

        return Array.from(brokerIds).sort((a, b) => a - b);
    };

    // Create rack groups including offline brokers
    const createRackGroups = () => {
        const allBrokerIds = getAllBrokerIds();
        const groups = new Map<string, BrokerInfo[]>();

        // First, add all known brokers to their racks
        Object.values(brokers).forEach(broker => {
            if (!groups.has(broker.rack)) {
                groups.set(broker.rack, []);
            }
            groups.get(broker.rack)!.push(broker);
        });

        // Then, add unknown/offline brokers to an "Unknown" rack
        allBrokerIds.forEach(id => {
            if (!Object.values(brokers).some(b => b.id === id)) {
                const unknownBroker: BrokerInfo = {
                    id,
                    rack: 'Unknown',
                    host: 'Offline',
                    port: 0
                };
                if (!groups.has('Unknown')) {
                    groups.set('Unknown', []);
                }
                groups.get('Unknown')!.push(unknownBroker);
            }
        });

        // Convert to array and sort
        return Array.from(groups.entries())
            .map(([rack, brokers]) => ({
                rack,
                brokers: brokers.sort((a, b) => a.id - b.id)
            }))
            .sort((a, b) => {
                // Put "Unknown" rack last
                if (a.rack === 'Unknown') return 1;
                if (b.rack === 'Unknown') return -1;
                return a.rack.localeCompare(b.rack);
            });
    };

    const rackGroups = createRackGroups();

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
                <Alert severity="error" sx={{ mb: 3 }}>
                    {error}
                </Alert>
            )}

            {isLoading ? (
                <Box sx={{ textAlign: 'center', py: 4 }}>
                    <Typography>Loading topic information...</Typography>
                </Box>
            ) : !error && (
                <>
                    <Box sx={{ mb: 3 }}>
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
                                    <TableCell align="center" rowSpan={2}>Partition</TableCell>
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
                                            Rack [{rackGroup.rack}]
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
                                                        Broker [{broker.id}]
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
                                        <TableCell align="center">{partition.partition}</TableCell>
                                        {rackGroups.map((rackGroup) => (
                                            rackGroup.brokers.map((broker) => (
                                                <TableCell
                                                    key={broker.id}
                                                    align="center"
                                                    sx={{ borderLeft: '1px solid rgba(224, 224, 224, 1)' }}
                                                >
                                                    {getStatusChip(getPartitionStatus(partition, broker.id))}
                                                </TableCell>
                                            ))
                                        ))}
                                    </TableRow>
                                ))}
                            </TableBody>
                        </Table>
                    </TableContainer>
                </>
            )}
        </Container>
    );
};

export default TopicDetail;