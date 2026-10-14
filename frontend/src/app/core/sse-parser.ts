/** One Server-Sent Events message: the `event:` name (default "message") and its `data:` text. */
export interface SseMessage {
  event: string;
  data: string;
}

/**
 * Incremental parser for the text/event-stream format. Feed it text as it arrives (chunks can end
 * mid-line or mid-message); it returns the messages that are complete so far.
 *
 *   event: request            <- name
 *   data: {"requestId":7}     <- payload (several data lines are joined with "\n")
 *                             <- blank line ends the message
 *   :heartbeat                <- comment, ignored
 */
export function createSseParser(): (chunk: string) => SseMessage[] {
  let buffer = '';
  return (chunk) => {
    buffer = (buffer + chunk).replace(/\r\n/g, '\n');
    const messages: SseMessage[] = [];
    let end: number;
    while ((end = buffer.indexOf('\n\n')) >= 0) {
      const block = buffer.slice(0, end);
      buffer = buffer.slice(end + 2);
      let event = 'message';
      const data: string[] = [];
      for (const line of block.split('\n')) {
        if (line === '' || line.startsWith(':')) continue;
        const colon = line.indexOf(':');
        const field = colon < 0 ? line : line.slice(0, colon);
        let value = colon < 0 ? '' : line.slice(colon + 1);
        if (value.startsWith(' ')) value = value.slice(1);
        if (field === 'event') event = value;
        else if (field === 'data') data.push(value);
      }
      if (data.length > 0) messages.push({ event, data: data.join('\n') });
    }
    return messages;
  };
}
