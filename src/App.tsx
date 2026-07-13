import { BrowserRouter as Router, Routes, Route } from "react-router-dom";
import Layout from "@/components/Layout/Layout";
import Home from "@/pages/Home";
import Weather from "@/pages/Weather";
import Travel from "@/pages/Travel";
import TravelDetail from "@/pages/TravelDetail";
import Reminders from "@/pages/Reminders";
import Todos from "@/pages/Todos";
import BabyPlan from "@/pages/BabyPlan";

export default function App() {
  return (
    <Router>
      <Routes>
        <Route element={<Layout />}>
          <Route path="/" element={<Home />} />
          <Route path="/weather" element={<Weather />} />
          <Route path="/travel" element={<Travel />} />
          <Route path="/travel/:id" element={<TravelDetail />} />
          <Route path="/reminders" element={<Reminders />} />
          <Route path="/todos" element={<Todos />} />
          <Route path="/baby" element={<BabyPlan />} />
        </Route>
      </Routes>
    </Router>
  );
}
