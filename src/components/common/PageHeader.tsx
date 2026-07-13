interface PageHeaderProps {
  title: string;
  subtitle?: React.ReactNode;
  rightElement?: React.ReactNode;
}

export default function PageHeader({ title, subtitle, rightElement }: PageHeaderProps) {
  return (
    <div className="px-5 pt-8 pb-4">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-800 font-rounded">{title}</h1>
          {subtitle && (
            <p className="text-sm text-gray-500 mt-1">{subtitle}</p>
          )}
        </div>
        {rightElement}
      </div>
    </div>
  );
}
