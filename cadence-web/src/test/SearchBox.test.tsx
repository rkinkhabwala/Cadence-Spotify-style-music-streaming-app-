import { useState } from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SearchBox } from '../components/SearchBox';
import { fakeApi } from './fixtures';

describe('SearchBox', () => {
  let api: ReturnType<typeof fakeApi>;

  beforeEach(() => {
    api = fakeApi({
      'GET /search/suggest': (url: URL) => ({
        query: url.searchParams.get('q'),
        items: [
          { type: 'artist', id: 'ar9', text: 'The Beatlz', subtitle: null, imageUrl: null },
          { type: 'track', id: 't9', text: 'Beat Street', subtitle: 'Nova Lights', imageUrl: null },
        ],
      }),
    });
    vi.stubGlobal('fetch', vi.fn(api.fetchMock));
  });
  afterEach(() => vi.unstubAllGlobals());

  function setup() {
    const onSubmit = vi.fn();
    function Controlled() {
      const [value, setValue] = useState('');
      return <SearchBox value={value} onChange={setValue} onSubmit={onSubmit} />;
    }
    const router = createMemoryRouter([
      { path: '/', element: <Controlled /> },
      { path: '/artist/:id', element: <p>artist page</p> },
    ]);
    render(<RouterProvider router={router} />);
    return { onSubmit, router };
  }

  it('suggests as you type (debounced) and opens the chosen artist with the keyboard', async () => {
    const user = userEvent.setup();
    const { router } = setup();
    await user.type(screen.getByRole('combobox'), 'beatls');

    const options = await screen.findAllByRole('option');
    expect(options[0]).toHaveTextContent('The Beatlz');
    expect(options[0]).toHaveTextContent('artist');
    const suggestCalls = api.calls.filter((c) => c.path === '/search/suggest');
    expect(suggestCalls.length).toBeLessThan(6);   // debounced: not one request per keystroke

    await user.keyboard('{ArrowDown}{Enter}');
    await waitFor(() => expect(router.state.location.pathname).toBe('/artist/ar9'));
  });

  it('Enter without a highlighted suggestion runs a full search', async () => {
    const user = userEvent.setup();
    const { onSubmit } = setup();
    await user.type(screen.getByRole('combobox'), 'beat{Enter}');
    expect(onSubmit).toHaveBeenCalledWith('beat');
  });
});
