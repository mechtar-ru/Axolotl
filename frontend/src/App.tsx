import React, { useEffect, useState } from 'react';
import WorkflowCanvas from './components/Canvas/WorkflowCanvas';
import { api } from './services/api';
import { WorkflowSchema } from './types';
import './App.css';

function App() {
  const [schemas, setSchemas] = useState<WorkflowSchema[]>([]);
  const [currentSchema, setCurrentSchema] = useState<WorkflowSchema | null>(null);
  const [loading, setLoading] = useState(true);
  const [showMermaid, setShowMermaid] = useState(false);
  const [showImport, setShowImport] = useState(false);
  const [mermaidCode, setMermaidCode] = useState('');
  const [importText, setImportText] = useState('');

  useEffect(() => {
    loadSchemas();
  }, []);

  const loadSchemas = async () => {
    try {
      const data = await api.getSchemas();
      setSchemas(data);
      if (data.length > 0) {
        setCurrentSchema(data[0]);
      }
    } catch (error) {
      console.error('Ошибка загрузки схем:', error);
    } finally {
      setLoading(false);
    }
  };

  const generateUniqueName = (baseName: string, existingSchemas: WorkflowSchema[]): string => {
    const names = existingSchemas.map(s => s.name);
    if (!names.includes(baseName)) return baseName;
    
    let counter = 1;
    while (names.includes(`${baseName} (${counter})`)) {
      counter++;
    }
    return `${baseName} (${counter})`;
  };

  const handleSave = async () => {
    if (currentSchema) {
      try {
        const saved = await api.updateSchema(currentSchema.id, currentSchema);
        // Обновляем схему в списке
        setSchemas(prev => prev.map(s => s.id === saved.id ? saved : s));
        setCurrentSchema(saved);
        alert('Схема сохранена!');
      } catch (error) {
        console.error('Ошибка сохранения:', error);
        alert('Ошибка при сохранении схемы');
      }
    }
  };

  const handleExecute = async () => {
    if (currentSchema) {
      try {
        await api.executeSchema(currentSchema.id);
        alert('Выполнение запущено! Следите за прогрессом в консоли сервера.');
      } catch (error) {
        console.error('Ошибка выполнения:', error);
        alert('Ошибка при запуске выполнения');
      }
    }
  };

  const handleExportMermaid = async () => {
    if (currentSchema) {
      try {
        const mermaid = await api.exportToMermaid(currentSchema.id);
        setMermaidCode(mermaid);
        setShowMermaid(true);
      } catch (error) {
        console.error('Ошибка экспорта:', error);
        alert('Ошибка при экспорте в Mermaid');
      }
    }
  };

  const copyToClipboard = async () => {
    try {
      await navigator.clipboard.writeText(mermaidCode);
      alert('Mermaid код скопирован в буфер обмена!');
    } catch (error) {
      console.error('Ошибка копирования:', error);
      alert('Не удалось скопировать текст');
    }
  };

  const saveToFile = () => {
    try {
      const blob = new Blob([mermaidCode], { type: 'text/markdown' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `${currentSchema?.name || 'schema'}-mermaid.md`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
      alert('Файл сохранён!');
    } catch (error) {
      console.error('Ошибка сохранения файла:', error);
      alert('Не удалось сохранить файл');
    }
  };

  const importFromMermaid = async () => {
    // Парсинг Mermaid в схему (упрощённая версия)
    try {
      const lines = importText.split('\n');
      const nodes: any[] = [];
      const edges: any[] = [];
      
      for (const line of lines) {
        // Поиск определений узлов: id["label"]
        const nodeMatch = line.match(/(\w+)\[?"?([^"\]]+)"?\]?/);
        if (nodeMatch && !line.includes('-->') && !line.includes('---')) {
          nodes.push({
            id: nodeMatch[1],
            name: nodeMatch[2] || nodeMatch[1],
            type: 'agent',
            position: { x: 100 + nodes.length * 150, y: 100 + nodes.length * 50 },
            data: { userPrompt: '' },
            status: 'idle'
          });
        }
        
        // Поиск связей: source --> target
        const edgeMatch = line.match(/(\w+)\s*-->\s*(\w+)/);
        if (edgeMatch) {
          edges.push({
            id: `edge-${Date.now()}-${edges.length}`,
            source: edgeMatch[1],
            target: edgeMatch[2],
            type: 'data'
          });
        }
      }
      
      const newSchema: WorkflowSchema = {
        id: `imported-${Date.now()}`,
        name: generateUniqueName('Импортированная схема', schemas),
        description: 'Импортировано из Mermaid',
        version: '1.0',
        nodes: nodes,
        edges: edges,
        createdAt: new Date().toISOString(),
      };
      
      const created = await api.createSchema(newSchema);
      setSchemas([...schemas, created]);
      setCurrentSchema(created);
      setShowImport(false);
      setImportText('');
      alert('Схема импортирована!');
    } catch (error) {
      console.error('Ошибка импорта:', error);
      alert('Ошибка при импорте Mermaid схемы');
    }
  };

  const handleFileUpload = (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (file) {
      const reader = new FileReader();
      reader.onload = (e) => {
        setImportText(e.target?.result as string);
      };
      reader.readAsText(file);
    }
  };

  if (loading) return <div className="loading">Загрузка...</div>;

  return (
    <div className="app">
      <div className="sidebar">
        <h2>📋 Схемы</h2>
        <ul>
          {schemas.map(schema => (
            <li
              key={schema.id}
              className={currentSchema?.id === schema.id ? 'active' : ''}
              onClick={() => setCurrentSchema(schema)}
            >
              {schema.name}
            </li>
          ))}
        </ul>
        <button onClick={async () => {
          const newName = generateUniqueName('Новая схема', schemas);
          const newSchema: WorkflowSchema = {
            id: `new-${Date.now()}`,
            name: newName,
            description: '',
            version: '1.0',
            nodes: [],
            edges: [],
            createdAt: new Date().toISOString(),
          };
          try {
            const schema = await api.createSchema(newSchema);
            setSchemas([...schemas, schema]);
            setCurrentSchema(schema);
          } catch (error) {
            console.error('Ошибка создания:', error);
            alert('Не удалось создать схему');
          }
        }}>
          + Новая схема
        </button>
        <button onClick={() => setShowImport(true)} style={{ marginTop: '10px', background: '#4caf50' }}>
          📥 Импорт
        </button>
      </div>
      
      <div className="canvas-container">
        {currentSchema && (
          <WorkflowCanvas
            schema={currentSchema}
            onUpdate={setCurrentSchema}
          />
        )}
      </div>
      
      <div className="toolbar">
        <button onClick={handleSave}>💾 Сохранить</button>
        <button onClick={handleExecute}>▶ Выполнить</button>
        <button onClick={handleExportMermaid}>📊 Экспорт Mermaid</button>
      </div>
      
      {showMermaid && (
        <div className="modal">
          <div className="modal-content">
            <h3>📊 Mermaid диаграмма</h3>
            <pre>{mermaidCode}</pre>
            <div className="modal-buttons">
              <button onClick={copyToClipboard}>📋 Скопировать в буфер</button>
              <button onClick={saveToFile}>💾 Сохранить в файл</button>
              <button onClick={() => setShowMermaid(false)}>❌ Закрыть</button>
            </div>
          </div>
        </div>
      )}
      
      {showImport && (
        <div className="modal">
          <div className="modal-content">
            <h3>📥 Импорт Mermaid схемы</h3>
            <textarea
              value={importText}
              onChange={(e) => setImportText(e.target.value)}
              placeholder="Вставьте Mermaid код здесь..."
              rows={10}
              style={{ width: '100%', margin: '10px 0', padding: '8px', fontFamily: 'monospace' }}
            />
            <div style={{ margin: '10px 0' }}>
              <input type="file" accept=".md,.txt" onChange={handleFileUpload} />
            </div>
            <div className="modal-buttons">
              <button onClick={importFromMermaid}>📥 Импортировать</button>
              <button onClick={() => { setShowImport(false); setImportText(''); }}>❌ Отмена</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

export default App;
