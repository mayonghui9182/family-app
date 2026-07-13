import { Outlet, useLocation } from 'react-router-dom';
import BottomNav from './BottomNav';
import { useEffect, useState } from 'react';

export default function Layout() {
  const location = useLocation();
  const [visible, setVisible] = useState(false);

  useEffect(() => {
    setVisible(false);
    const timer = setTimeout(() => setVisible(true), 50);
    return () => clearTimeout(timer);
  }, [location.pathname]);

  return (
    <div className="min-h-screen bg-gradient-to-br from-cream-50 to-cream-100">
      <div className="mx-auto max-w-[480px] min-h-screen bg-gradient-to-b from-cream-50 to-white relative pb-24">
        <main
          key={location.pathname}
          className={`transition-all duration-300 ${
            visible ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-4'
          }`}
        >
          <Outlet />
        </main>
        <BottomNav />
      </div>
    </div>
  );
}
