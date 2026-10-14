import { createSseParser } from './sse-parser';

describe('createSseParser', () => {
  it('parses named events with JSON data', () => {
    const parse = createSseParser();
    expect(parse('event:request\ndata:{"requestId":7}\n\nevent:ready\ndata:{}\n\n')).toEqual([
      { event: 'request', data: '{"requestId":7}' },
      { event: 'ready', data: '{}' },
    ]);
  });

  it('keeps incomplete messages until the rest arrives (chunks split anywhere)', () => {
    const parse = createSseParser();
    expect(parse('event:requ')).toEqual([]);
    expect(parse('est\ndata:{"request')).toEqual([]);
    expect(parse('Id":7}\n')).toEqual([]);
    expect(parse('\n')).toEqual([{ event: 'request', data: '{"requestId":7}' }]);
  });

  it('ignores heartbeat comments, handles CRLF, optional space and multi-line data', () => {
    const parse = createSseParser();
    expect(parse(':heartbeat\n\n')).toEqual([]);
    expect(parse('event: notification\r\ndata: line1\r\ndata: line2\r\n\r\n')).toEqual([
      { event: 'notification', data: 'line1\nline2' },
    ]);
    expect(parse('data:x\n\n')).toEqual([{ event: 'message', data: 'x' }]);
  });
});
