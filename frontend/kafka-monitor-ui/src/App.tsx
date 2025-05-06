import React from 'react';
import { BrowserRouter as Router, Routes, Route } from 'react-router-dom';
import TopicsList from './components/TopicsList';
import TopicDetail from './components/TopicDetail';

function App() {
    return (
        <Router>
            <Routes>
                <Route path="/" element={<TopicsList />} />
                <Route path="/topic/:topicName" element={<TopicDetail />} />
            </Routes>
        </Router>
    );
}

export default App;